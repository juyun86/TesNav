"""Exercise the actual HTTP handler and selector with deterministic camera packets."""
import ast
import asyncio
import json
from pathlib import Path
import struct
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch

HERE=Path(__file__).parent

class CameraSwitchTest(unittest.IsolatedAsyncioTestCase):
    def selector(self):
        tree=ast.parse((HERE/'c3_ar_source.py').read_text(encoding='utf-8'))
        cls=next(n for n in tree.body if isinstance(n,ast.ClassDef) and n.name=='Client')
        method=next(n for n in cls.body if isinstance(n,ast.FunctionDef) and n.name=='selected_native_camera')
        class State(dict): pass
        state=State(carState=SimpleNamespace(vEgo=5),selfdriveState=SimpleNamespace(experimentalMode=True))
        state.alive={n:True for n in ('carState','selfdriveState','wideRoadCameraState')}
        state.valid=state.alive.copy()
        state.logMonoTime={n:time.monotonic_ns() for n in state.alive}
        env={'state':state,'time':time,'math':__import__('math')}
        exec(compile(ast.Module(body=[method],type_ignores=[]),'<selector>','exec'),env)
        return state,env['selected_native_camera']

    def test_speed_hysteresis(self):
        state,select=self.selector();client=SimpleNamespace(wide=False)
        for speed,expected in [(5,'wideRoad'),(10,'wideRoad'),(15,'wideRoad'),(16,'narrowRoad'),(12,'narrowRoad'),(9,'wideRoad')]:
            state['carState'].vEgo=speed
            self.assertEqual(expected,select(client));client.wide=expected=='wideRoad'
        state['selfdriveState'].experimentalMode=False
        self.assertEqual('narrowRoad',select(client))

    def test_old_speed_does_not_switch(self):
        state,select=self.selector();client=SimpleNamespace(wide=False)
        state.logMonoTime['carState']-=2_000_000_000
        self.assertEqual('narrowRoad',select(client))

    async def test_same_connection_wide_narrow_wide(self):
        state,select=self.selector();output=[];workers=[]
        class Client:
            def __init__(self,native): self.wide=False
            selected_native_camera=select
            def describe(self,fid,ts,w,h):
                return None,{'camera':'wideRoad' if self.wide else 'narrowRoad','frameId':fid}
        class Response:
            def __init__(self,headers):self.headers=headers
            async def prepare(self,request):pass
            async def write(self,body):
                n=struct.unpack_from('!I',body,8)[0]
                meta=json.loads(body[12:12+n]);output.append(meta)
                if not meta['config']:
                    state['carState'].vEgo=16 if len(output)==2 else 5
        class Worker:
            def __init__(self,camera):
                self.returncode=None;self.stdout=asyncio.StreamReader()
                crop=[464,330,1424,870] if camera=='wideRoad' else [340,462,1620,1182]
                for config in (True,False,False):
                    meta={'camera':camera,'crop':crop,'width':1280,'height':720,
                        'sourceWidth':1928,'sourceHeight':1208,'config':config,'frameId':1,
                        'timestampEofNs':1000,'ptsUs':1}
                    text=json.dumps(meta).encode();body=b'CAH3'+struct.pack('!I',len(text))+text+b'payload'
                    self.stdout.feed_data(struct.pack('!I',len(body))+body)
            def terminate(self):self.returncode=0
            def kill(self):self.returncode=-9
            async def communicate(self):return b'',b''
        async def launch(*args,**kwargs):
            worker=Worker(args[3]);workers.append(worker);return worker
        class WorkerPath:
            def __truediv__(self,name):return self
            def is_file(self):return True
            def __str__(self):return 'test-worker'
        env=dict(asyncio=asyncio,json=json,struct=struct,time=time,web=SimpleNamespace(StreamResponse=Response))
        tree=ast.parse((HERE/'ar_h264_stream.py').read_text(encoding='utf-8'))
        fn=next(n for n in tree.body if isinstance(n,ast.AsyncFunctionDef) and n.name=='stream_h264')
        exec(compile(ast.Module(body=[fn],type_ignores=[]),'<stream>','exec'),env)
        request=SimpleNamespace(headers={'X-C3-AR-Native':'1','X-C3-AR-Geometry':'1'},transport=SimpleNamespace(is_closing=lambda:False))
        stats={'connections':0,'frames':0}
        with patch('asyncio.create_subprocess_exec',launch):
            await env['stream_h264'](request,0,Client,stats,lambda:len(output)<6,
                {'camera':'narrowRoad','crop':[340,462,1620,1182],'sourceSize':[1928,1208]},WorkerPath())
        self.assertEqual(['wideRoad','wideRoad','narrowRoad','narrowRoad','wideRoad','wideRoad'],[m['camera'] for m in output])
        self.assertEqual([True,False]*3,[m['config'] for m in output])
        self.assertEqual(1,stats['connections']);self.assertEqual(3,stats['frames'])
        self.assertTrue(all(w.returncode==0 for w in workers));self.assertFalse(stats['active'])

if __name__=='__main__':unittest.main()
