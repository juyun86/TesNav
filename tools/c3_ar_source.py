"""User-authorized, on-demand read-only subscription to an already running road camera.
No Params writes, preview activation, process restart, control or CAN operations.
"""
import asyncio,os,struct,sys,time,json,math
from pathlib import Path
from types import SimpleNamespace
HERE=Path(__file__).resolve().parent
NATIVE_PROFILE=json.loads((HERE/'native_crop.json').read_text()) if (HERE/'native_crop.json').exists() else None
sys.path[:0]=[str(HERE/'deps'),'/data/openpilot','/data/openpilot/opendbc_repo','/data/openpilot/msgq_repo']
os.nice(19)
os.sched_setaffinity(0,{0,1,2,3})
import c3_webd as upstream
from aiohttp import web
from openpilot.cereal import messaging
from msgq.visionipc import VisionIpcClient
from openpilot.cereal.visionipc import VisionStreamType
from openpilot.common.transformations.camera import DEVICE_CAMERAS,view_frame_from_device_frame
from openpilot.common.transformations.orientation import rot_from_euler
state=messaging.SubMaster(['deviceState','managerState','modelV2','wideRoadCameraState',
                          'narrowRoadCameraState','carState','selfdriveState','extrinsicsCalibration','radarState'])
token=(HERE/'stream.token').read_text().strip()
lock=asyncio.Lock()
stats={'connections':0,'frames':0,'active':False,'fpsLimit':20,'maxSize':[1280,802],'jpegQuality':88,
       'camera':'pending','crop':'C3 UI viewport','uiViewport':[2100,1020],
       'encoder':'downsample NV12 then encode YCbCr JPEG without RGB expansion'}

def native_crop(width,height,sensor,wide=False):
    if wide:
        if (width,height,sensor)!=(1928,1208,'ox03c10'):
            raise ValueError('wide camera identity not validated')
        return (464.,330.,1424.,870.)
    # A fixed virtual framing adjustment, not a change to the device calibration.
    if NATIVE_PROFILE is None:return (0.,0.,float(width),float(height))
    assert NATIVE_PROFILE['version']==1 and NATIVE_PROFILE['sourceSize']==[width,height], 'native crop source dimensions changed'
    assert NATIVE_PROFILE['sensor']==sensor, 'native crop sensor changed'
    assert NATIVE_PROFILE.get('camera','narrowRoad') in ('narrowRoad','wideRoad'), 'invalid native camera'
    crop=tuple(float(v) for v in NATIVE_PROFILE['crop'])
    assert len(crop)==4 and all(math.isfinite(v) for v in crop)
    left,top,right,bottom=crop
    assert 0<=left<right<=width and 0<=top<bottom<=height
    assert abs((right-left)*9-(bottom-top)*16)<1e-6
    return crop

def ui_crop(width,height,intrinsic,calibration,wide):
    """Same view transform as AugmentedRoadView._calc_frame_matrix, without HUD.
    Standard C3 full-screen 2160x1080 minus its 30px border on each edge.
    This is a viewport transform, NOT a lens-distortion calibration.
    """
    np=upstream.np
    w,h=2100,1020
    cx,cy=intrinsic[0,2],intrinsic[1,2]
    zoom=max(2.0 if wide else 1.1,w/(2*cx),h/(2*cy))
    kep=intrinsic @ calibration @ np.array([1000.,0.,0.])
    ox=oy=0.
    if abs(kep[2])>1e-6:
        ox=float(np.clip((kep[0]/kep[2]-cx)*zoom,-max(0,cx*zoom-w/2-5),max(0,cx*zoom-w/2-5)))
        oy=float(np.clip((kep[1]/kep[2]-cy)*zoom,-max(0,cy*zoom-h/2-5),max(0,cy*zoom-h/2-5)))
    left,top=cx+(ox-w/2)/zoom,cy+(oy-h/2)/zoom
    right,bottom=left+w/zoom,top+h/zoom
    assert 0<=left<right<=width and 0<=top<bottom<=height
    return left,top,right,bottom

class TrialJpegEncoder:
    """Reuse Pillow JPEG, without full-resolution RGB or a per-pixel float dot product."""
    def __init__(self,geometry=False):self.sample=None;self.geometry=geometry
    def encode(self,frame):
        np=upstream.np
        width,height,stride=frame.width,frame.height,frame.stride
        left,top,right,bottom=frame.crop
        scale=min(1,1280/(right-left),802/(bottom-top))
        w,h=max(2,int((right-left)*scale)&~1),max(2,int((bottom-top)*scale)&~1)
        stats['sourceSize']=[width,height];stats['encodedSize']=[w,h]
        stats['sourceCrop']=[round(v,3) for v in frame.crop]
        shape=(width,height,w,h,frame.crop)
        if self.sample is None or self.sample[0]!=shape:
            self.sample=(shape,np.linspace(top,bottom-1,h,dtype=int),np.linspace(left,right-1,w,dtype=int),
                         np.linspace(top/2,(bottom-2)/2,h//2,dtype=int),np.linspace(left/2,(right-2)/2,w//2,dtype=int))
        _,yr,yc,ur,uc=self.sample
        y=np.frombuffer(frame.data,dtype=np.uint8,count=height*stride).reshape(height,stride)[:,:width]
        uv=np.frombuffer(frame.data,dtype=np.uint8,count=(height//2)*stride,offset=frame.uv_offset).reshape(height//2,stride)[:,:width].reshape(height//2,width//2,2)
        y_small=y[np.ix_(yr,yc)]
        uv_small=uv[np.ix_(ur,uc)]
        image=upstream.Image.merge('YCbCr',(
            upstream.Image.fromarray(y_small),
            upstream.Image.fromarray(uv_small[:,:,0]).resize((w,h),upstream.Image.Resampling.NEAREST),
            upstream.Image.fromarray(uv_small[:,:,1]).resize((w,h),upstream.Image.Resampling.NEAREST)))
        out=upstream.io.BytesIO();image.save(out,'JPEG',quality=88,subsampling=2)
        jpeg=out.getvalue()
        if not self.geometry:return jpeg
        frame.geometry['sentElapsedMs']=time.monotonic_ns()/1e6
        frame.geometry['sourceAgeMs']=(time.monotonic_ns()-frame.timestamp_eof)/1e6
        meta=json.dumps(frame.geometry,separators=(',',':'),allow_nan=False).encode()
        return b'CAR2'+struct.pack('!I',len(meta))+meta+jpeg
    def close(self):pass

def running_camera():
    return (state.alive['deviceState'] and state['deviceState'].started and
            state.alive['managerState'] and state.alive['modelV2'] and state.valid['modelV2'] and
            state.alive['wideRoadCameraState'] and state.valid['wideRoadCameraState'] and
            all(state.alive[n] and state.valid[n] for n in ('narrowRoadCameraState','carState','selfdriveState','extrinsicsCalibration')) and
            str(state['deviceState'].thermalStatus) in ('ok','warmDEPRECATED') and
            any(p.name=='camerad' and p.running for p in state['managerState'].processes))

class Client:
    def __init__(self,native=False):
        self.native=native
        self.client=None;self.wide=False
        self.calibration=view_frame_from_device_frame.copy()
        self.wide_calibration=view_frame_from_device_frame.copy()
    def selected_native_camera(self):
        target=self.wide
        names=('carState','selfdriveState','wideRoadCameraState')
        now=time.monotonic_ns()
        if all(state.alive[n] and state.valid[n] and 0<=now-state.logMonoTime[n]<=1_000_000_000 for n in names):
            speed=float(state['carState'].vEgo)
            if not state['selfdriveState'].experimentalMode:target=False
            elif math.isfinite(speed) and speed>=0:
                if speed<10:target=True
                elif speed>15:target=False
        return 'wideRoad' if target else 'narrowRoad'

    def connect(self,block):
        target=self.wide
        if self.native:target=bool(NATIVE_PROFILE and NATIVE_PROFILE.get('camera')=='wideRoad')
        elif state['selfdriveState'].experimentalMode:
            if state['carState'].vEgo<10:target=True
            elif state['carState'].vEgo>15:target=False
        else:target=False
        if self.client is None or target!=self.wide:
            self.wide=target
            stream=VisionStreamType.VISION_STREAM_WIDE_ROAD if target else VisionStreamType.VISION_STREAM_NARROW_ROAD
            self.client=VisionIpcClient('camerad',stream,True)
            stats['camera']='wideRoad' if target else 'narrowRoad'
        return self.client.is_connected() or self.client.connect(False)
    def recv(self,timeout_ms=100):
        if not self.connect(False):return None
        frame=self.client.recv(timeout_ms)
        if frame is None:return None
        c=self.client
        crop,geometry=self.describe(int(c.frame_id),int(c.timestamp_eof),c.width,c.height)
        return SimpleNamespace(data=frame.data,width=c.width,height=c.height,stride=c.stride,uv_offset=c.uv_offset,crop=crop,geometry=geometry,timestamp_eof=int(c.timestamp_eof))

    def describe(self,frame_id,timestamp_eof,width,height):
        c=SimpleNamespace(frame_id=frame_id,timestamp_eof=timestamp_eof,width=width,height=height)
        sensor=str(state['narrowRoadCameraState'].sensor)
        config=DEVICE_CAMERAS[(str(state['deviceState'].deviceType),sensor)]
        camera=config.wide_road if self.wide else config.narrow_road
        assert (camera.width,camera.height)==(c.width,c.height)
        calib=state['extrinsicsCalibration']
        if str(calib.calStatus)=='calibrated' and len(calib.rpyCalib)==3:
            device_from_calib=rot_from_euler(calib.rpyCalib)
            self.calibration=view_frame_from_device_frame @ device_from_calib
            if len(calib.wideFromDeviceEuler)==3:
                self.wide_calibration=view_frame_from_device_frame @ rot_from_euler(calib.wideFromDeviceEuler) @ device_from_calib
        matrix=self.wide_calibration if self.wide else self.calibration
        crop=native_crop(c.width,c.height,sensor,self.wide) if self.native else ui_crop(c.width,c.height,camera.intrinsics,matrix,self.wide)
        model=state['modelV2']
        # Use the same camera frame IDs/timestamps that accompany this VisionIPC buffer.
        model_id=int(model.frameIdExtra if self.wide else model.frameId)
        lag_ms=(int(c.timestamp_eof)-int(model.timestampEof))/1e6
        geometry={'version':1,'frameId':int(c.frame_id),'modelFrameId':model_id,
                  'camera':'wideRoad' if self.wide else 'narrowRoad','lagMs':lag_ms,'lanes':[],
                  'inputMode':'native' if self.native else 'enhanced'}
        # Intrinsics belong to the delivered image, independently of model/pose validity.
        # The receiver applies the explicitly supplied crop and encoded-size transform.
        if self.native:
            k=camera.intrinsics
            geometry['cameraCalibration']={'version':1,'sensor':sensor,
                'sourceSize':[c.width,c.height],'crop':list(crop),
                'intrinsics':[float(k[0,0]),float(k[1,1]),float(k[0,2]),float(k[1,2])]}
        # Session calibration snapshot; never substitute the initialization constant.
        if 'cameraCalibration' in geometry:
            age=(time.monotonic_ns()-state.logMonoTime['extrinsicsCalibration'])/1e6
            if (state.alive['extrinsicsCalibration'] and state.valid['extrinsicsCalibration'] and
                str(calib.calStatus)=='calibrated' and int(calib.validBlocks)>0 and
                0<=age<=3000 and len(calib.height)==1):
                value=float(calib.height[0])
                if math.isfinite(value) and 0.5<=value<=3.0:
                    geometry['cameraCalibration'].update(heightM=value,heightStatus='calibrated')
        if self.native and self.wide:
            age=(time.monotonic_ns()-state.logMonoTime['extrinsicsCalibration'])/1e6
            if not (state.alive['extrinsicsCalibration'] and state.valid['extrinsicsCalibration'] and
                    str(calib.calStatus)=='calibrated' and int(calib.validBlocks)>0 and 0<=age<=3000 and
                    len(calib.rpyCalib)==3 and len(calib.wideFromDeviceEuler)==3 and
                    all(math.isfinite(float(v)) for v in (*calib.rpyCalib,*calib.wideFromDeviceEuler)) and
                    geometry['cameraCalibration'].get('heightStatus')=='calibrated'):
                raise ValueError('fresh wide camera calibration unavailable')
            geometry['cameraCalibration'].update(
                rpyCalib=[float(v) for v in calib.rpyCalib],
                wideFromDeviceEuler=[float(v) for v in calib.wideFromDeviceEuler])
        if (str(calib.calStatus)=='calibrated' and state.valid['modelV2'] and
            abs(int(c.frame_id)-model_id)<=3 and 0<=lag_ms<=150):
            from ar_lane_projection import project_lanes,project_paths
            lines=[upstream.np.array([l.x,l.y,l.z]).T for l in model.laneLines]
            edges=[upstream.np.array([l.x,l.y,l.z]).T for l in model.roadEdges]
            probs=list(model.laneLineProbs);stds=list(model.roadEdgeStds)
            geometry['lanes']=project_lanes(lines,probs,camera.intrinsics,matrix,crop,edges,stds)
            geometry['linePaths']=project_paths(lines,probs,camera.intrinsics,matrix,crop)
            geometry['edgePaths']=project_paths(edges,[max(0.,1-s) if s>=0 else 0. for s in stds],camera.intrinsics,matrix,crop)
            radar_age_ms=(time.monotonic_ns()-state.logMonoTime['radarState'])/1e6
            radar_delta_ms=abs(int(c.timestamp_eof)-state.logMonoTime['radarState'])/1e6
            if (state.alive['radarState'] and state.valid['radarState'] and
                0<=radar_age_ms<=300 and radar_delta_ms<=150 and len(calib.height)>0):
                from ar_lane_projection import project_leads
                radar=state['radarState']
                leads=[{'present':l.present,'dRel':l.dRel,'yRel':l.yRel,'vRel':l.vRel,'radar':l.radar}
                       for l in (radar.leadOne,radar.leadTwo)]
                geometry['leads']=project_leads(leads,list(model.position.x),list(model.position.z),
                    float(calib.height[0]),float(state['carState'].vEgo),camera.intrinsics,matrix,crop)
                geometry['radarDeltaMs']=radar_delta_ms
        return crop,geometry

async def health(request):
    ready=running_camera()
    return web.json_response({**stats,'ready':ready,'alive':dict(state.alive),'modelValid':bool(state.valid['modelV2']),
       'started':bool(state['deviceState'].started),'thermal':str(state['deviceState'].thermalStatus),
       'cameradRunning':any(p.name=='camerad' and p.running for p in state['managerState'].processes)})
async def clock_sample(request):
    if request.headers.get('Authorization')!='Bearer '+token:raise web.HTTPUnauthorized()
    return web.Response(text=str(time.monotonic_ns()/1e6),headers={'Cache-Control':'no-store'})
async def frames(request):
    request_epoch=time.monotonic_ns()/1e6
    if request.headers.get('Authorization')!='Bearer '+token:raise web.HTTPUnauthorized()
    if lock.locked():return web.Response(status=423,text='one viewer at a time')
    state.update(0)
    if not running_camera():raise web.HTTPConflict(text='existing onroad camera/model required')
    if request.headers.get('X-C3-AR-Codec')=='h264':
        from ar_h264_stream import stream_h264
        async with lock:
            return await stream_h264(request,request_epoch,Client,stats,running_camera,NATIVE_PROFILE,HERE)
    async with lock:
        geometry=request.headers.get('X-C3-AR-Geometry')=='1'
        native=request.headers.get('X-C3-AR-Native')=='1'
        native_camera='wide' if NATIVE_PROFILE and NATIVE_PROFILE.get('camera')=='wideRoad' else 'narrow'
        stats['mode']=('native-fixed-'+native_camera+'-crop' if NATIVE_PROFILE else 'native-fixed-narrow') if native else 'c3-ui'
        stats['crop']=('fixed native horizon crop' if NATIVE_PROFILE else 'full fixed camera') if native else 'C3 UI viewport'
        bc=upstream.FrameBroadcaster('road',fps=20,client_factory=lambda:Client(native),
                                    encoder_factory=lambda:TrialJpegEncoder(geometry))
        queue=None
        try:
            stats['active']=True
            await bc.start();queue=bc.subscribe()
            payload=await asyncio.wait_for(queue.get(),8)
            if not payload:
                stats['lastFrameError']=(bc.error or 'no existing frame')[:160]
                raise RuntimeError('no existing frame')
            response=web.StreamResponse(headers={'Content-Type':'application/octet-stream','Cache-Control':'no-store'})
            if geometry:
                response.headers['X-C3-AR-Protocol']='2'
                response.headers['X-C3-AR-Epoch-Ms']=str(request_epoch)
            await response.prepare(request);stats['connections']+=1
            while running_camera() and request.transport is not None and not request.transport.is_closing():
                try:
                    await asyncio.wait_for(response.write(struct.pack('!I',len(payload))+payload),4)
                except (asyncio.TimeoutError,ConnectionError):
                    # A stalled/disconnected receiver must release the single-viewer slot.
                    break
                stats['frames']+=1
                payload=await asyncio.wait_for(queue.get(),4)
                if not payload:
                    stats['lastFrameError']=(bc.error or 'camera reader stopped')[:160]
                    break
            return response
        finally:
            if queue is not None:bc.unsubscribe(queue)
            try:await bc.stop()
            finally:stats['active']=False

async def main():
    # Zero is the supervised service; positive values support bounded verification.
    lifetime=int(sys.argv[1]) if len(sys.argv)>1 else 0
    if not 0<=lifetime<=7200:raise ValueError('lifetime must be 0..7200 seconds')
    # Let SubMaster acquire fresh initial messages before the first phone request.
    for _ in range(20):state.update(0);await asyncio.sleep(.1)
    async def refresh_state():
        while True:
            state.update(0)
            await asyncio.sleep(.05 if stats['active'] else .5)
    refresh=asyncio.create_task(refresh_state())
    app=web.Application();app.router.add_get('/health',health);app.router.add_get('/frames',frames)
    app.router.add_get('/clock',clock_sample)
    runner=web.AppRunner(app,access_log=None);await runner.setup()
    await web.TCPSite(runner,'0.0.0.0',8082).start()
    # Supervised availability does not keep a camera worker or encoder alive.
    try:
        if lifetime:await asyncio.sleep(lifetime)
        else:await asyncio.Event().wait()
    finally:
        refresh.cancel()
        await asyncio.gather(refresh,return_exceptions=True)
        await runner.cleanup()
if __name__=='__main__':asyncio.run(main())
