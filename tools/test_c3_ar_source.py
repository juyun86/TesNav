"""Exercise the actual async handler without a camera, network or C3 dependencies."""
import ast
import asyncio
from pathlib import Path
from types import SimpleNamespace
import struct
import time
import unittest


class ReconnectTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        tree = ast.parse(Path(__file__).with_name('c3_ar_source.py').read_text())
        handler = next(n for n in tree.body if isinstance(n, ast.AsyncFunctionDef) and n.name == 'frames')
        self.instances = []
        owner = self

        class Broadcaster:
            def __init__(self, *args, **kwargs):
                self.stopped = self.unsubscribed = False
                owner.instances.append(self)
            async def start(self): pass
            def subscribe(self):
                q = asyncio.Queue()
                q.put_nowait(b'jpeg')
                q.put_nowait(None)
                return q
            def unsubscribe(self, q): self.unsubscribed = True
            async def stop(self): self.stopped = True

        class Response:
            def __init__(self, headers): self.headers = headers
            async def prepare(self, request): self.request = request
            async def write(self, data):
                if owner.disconnect: raise ConnectionResetError()

        self.disconnect = False
        self.env = dict(asyncio=asyncio, time=time, struct=struct, token='dummy',
            lock=asyncio.Lock(), stats=dict(connections=0, frames=0, active=False),
            state=SimpleNamespace(update=lambda timeout:None),
            running_camera=lambda: True, Client=object, TrialJpegEncoder=object,
            upstream=SimpleNamespace(FrameBroadcaster=Broadcaster),
            web=SimpleNamespace(HTTPUnauthorized=PermissionError, HTTPConflict=lambda **kw: RuntimeError(), HTTPLocked=lambda **kw: BlockingIOError(),
                                StreamResponse=Response))
        exec(compile(ast.Module(body=[handler], type_ignores=[]), '<source-handler>', 'exec'), self.env)
        self.request = SimpleNamespace(headers={'Authorization':'Bearer dummy'},
                                       transport=SimpleNamespace(is_closing=lambda:False))
        clock = next(n for n in tree.body if isinstance(n, ast.AsyncFunctionDef) and n.name == 'clock_sample')
        self.env['web'].Response = lambda **kw: SimpleNamespace(**kw)
        exec(compile(ast.Module(body=[clock], type_ignores=[]), '<clock-handler>', 'exec'), self.env)

    async def test_clock_is_authenticated_fresh_and_does_not_start_capture(self):
        before = time.monotonic_ns()/1e6
        response = await self.env['clock_sample'](self.request)
        self.assertTrue(before <= float(response.text) <= time.monotonic_ns()/1e6)
        self.assertEqual('no-store', response.headers['Cache-Control'])
        self.assertEqual([], self.instances)
        self.request.headers = {}
        with self.assertRaises(PermissionError): await self.env['clock_sample'](self.request)

    async def test_second_viewer_after_end(self):
        for _ in range(2): await self.env['frames'](self.request)
        self.assertEqual(2, self.env['stats']['connections'])
        self.assertTrue(all(b.stopped and b.unsubscribed for b in self.instances))
        self.assertFalse(self.env['stats']['active'])
        self.assertFalse(self.env['lock'].locked())

    async def test_disconnect_releases_capture_and_allows_reconnect(self):
        self.disconnect = True
        with self.assertRaises(ConnectionResetError): await self.env['frames'](self.request)
        self.assertTrue(self.instances[0].stopped)
        self.assertFalse(self.env['stats']['active'])
        self.disconnect = False
        await self.env['frames'](self.request)
        self.assertEqual(2, self.env['stats']['connections'])

    async def test_guard_and_auth_do_not_start_capture(self):
        self.request.headers = {}
        with self.assertRaises(PermissionError): await self.env['frames'](self.request)
        self.request.headers = {'Authorization':'Bearer dummy'}
        self.env['running_camera'] = lambda:False
        with self.assertRaises(RuntimeError): await self.env['frames'](self.request)
        self.assertEqual([], self.instances)

    async def test_second_simultaneous_viewer_rejected(self):
        async with self.env['lock']:
            with self.assertRaises(BlockingIOError): await self.env['frames'](self.request)
        self.assertEqual([], self.instances)


if __name__ == '__main__': unittest.main()
