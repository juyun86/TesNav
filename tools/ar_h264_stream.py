"""Authenticated caller already owns the common source slot. No camera activation."""
import asyncio,json,struct,time
from aiohttp import web

async def stream_h264(request,epoch,Client,stats,eligible,profile,base):
    worker=base/'h264-prototype/ar_h264_worker'
    if (request.headers.get('X-C3-AR-Native')!='1' or request.headers.get('X-C3-AR-Geometry')!='1' or
        not profile or profile.get('camera')!='narrowRoad' or not worker.is_file()):
        raise web.HTTPNotAcceptable(text='AVC native mode unavailable')
    projection=Client(True)
    process=None;response=None;camera=None;crop=None
    stats.update(active=True,mode='native-h264-speed-switch',encodedSize=[1280,720])
    async def stop_worker():
        nonlocal process
        if process is not None and process.returncode is None:
            process.terminate()
            try:await asyncio.wait_for(process.communicate(),2)
            except asyncio.TimeoutError:
                process.kill();await process.communicate()
        process=None
    async def packet():
        size=struct.unpack('!I',await asyncio.wait_for(process.stdout.readexactly(4),4))[0]
        if not 12<=size<=2_016_392:raise ValueError('AVC packet size')
        body=await asyncio.wait_for(process.stdout.readexactly(size),4)
        if body[:4]!=b'CAH3':raise ValueError('AVC packet version')
        length=struct.unpack('!I',body[4:8])[0]
        if not 2<=length<=16384 or length+8>=size:raise ValueError('AVC metadata length')
        meta=json.loads(body[8:8+length]);data=body[8+length:]
        if (meta['camera']!=camera or meta['crop']!=crop or meta['width']!=1280 or meta['height']!=720 or
            [meta['sourceWidth'],meta['sourceHeight']]!=profile['sourceSize']):raise ValueError('AVC geometry changed')
        if not meta['config']:
            if meta['timestampEofNs']//1000!=meta['ptsUs']:raise ValueError('AVC timestamp mismatch')
            _,geometry=projection.describe(meta['frameId'],meta['timestampEofNs'],meta['sourceWidth'],meta['sourceHeight'])
            now=time.monotonic_ns();geometry['sourceAgeMs']=(now-meta['timestampEofNs'])/1e6
            geometry['sentElapsedMs']=now/1e6
            meta['geometry']=geometry
        encoded=json.dumps(meta,separators=(',',':'),allow_nan=False).encode()
        if len(encoded)>16384:raise ValueError('AVC geometry too large')
        output=b'CAH3'+struct.pack('!I',len(encoded))+encoded+data
        return struct.pack('!I',len(output))+output,not meta['config']
    try:
        while eligible() and request.transport is not None and not request.transport.is_closing():
            selected=projection.selected_native_camera()
            if selected!=camera:
                await stop_worker()
                camera=selected;projection.wide=camera=='wideRoad'
                crop=[464,330,1424,870] if projection.wide else profile['crop']
                left,top,right,bottom=[int(v) for v in crop]
                projection.describe(0,0,*profile['sourceSize'])
                process=await asyncio.create_subprocess_exec(str(worker),'--run','0',camera,
                    str(left),str(top),str(right-left),str(bottom-top),'4000000',
                    stdin=asyncio.subprocess.DEVNULL,stdout=asyncio.subprocess.PIPE,stderr=asyncio.subprocess.DEVNULL)
                stats.update(camera=camera,sourceCrop=crop,sourceSize=profile['sourceSize'])
                stats['switches']=stats.get('switches',0)+1
            body,frame=await packet()
            # Do not ship an old worker's queued frames after a speed transition.
            if not eligible():break
            if projection.selected_native_camera()!=camera:continue
            if response is None:
                response=web.StreamResponse(headers={'Content-Type':'application/octet-stream','Cache-Control':'no-store',
                    'X-C3-AR-Protocol':'3','X-C3-AR-Codec':'h264','X-C3-AR-Epoch-Ms':str(epoch)})
                await response.prepare(request);stats['connections']+=1
            await asyncio.wait_for(response.write(body),4)
            if frame:stats['frames']+=1
        if response is None:raise web.HTTPConflict(text='source no longer eligible')
        return response
    except (asyncio.TimeoutError,asyncio.IncompleteReadError,ConnectionError,ValueError,KeyError):
        if response is None:raise web.HTTPServiceUnavailable(text='AVC source unavailable')
        return response
    finally:
        await stop_worker()
        stats['active']=False
