// Isolated, bounded, single-camera consumer of an already running camerad.
// Reuses the production V4LEncoder; no Params writes or service/process startup.
#include <arpa/inet.h>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <csignal>
#include <cstring>
#include <deque>
#include <map>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <sys/resource.h>
#include <unistd.h>
#include <arm_neon.h>
#include <media/msm_media_info.h>
#include "system/loggerd/encoder/v4l_encoder.h"

// The production base constructor eagerly opens a cereal publisher. This
// callback-only consumer must not bind or publish on a production topic.
VideoEncoder::VideoEncoder(const EncoderInfo &info, int iw, int ih)
    : in_width(iw), in_height(ih), out_width(info.frame_width > 0 ? info.frame_width : iw),
      out_height(info.frame_height > 0 ? info.frame_height : ih), encoder_info(info) {}
void VideoEncoder::publisher_publish(int, uint32_t, VisionIpcBufExtra &, unsigned int,
    kj::ArrayPtr<capnp::byte>, kj::ArrayPtr<capnp::byte>) { std::abort(); }

static uint64_t monotonic_ns() {
  return std::chrono::duration_cast<std::chrono::nanoseconds>(
      std::chrono::steady_clock::now().time_since_epoch()).count();
}
static volatile sig_atomic_t stopped=0;
static void stop_worker(int) { stopped=1; }
static bool write_packet(const std::string &meta, const uint8_t *data, size_t size) {
  if (size > 2'000'000 || meta.size() > 16'384) return false;
  uint32_t total = htonl(8 + meta.size() + size), length = htonl(meta.size());
  return fwrite(&total, 4, 1, stdout) == 1 && fwrite("CAH3", 4, 1, stdout) == 1 &&
      fwrite(&length, 4, 1, stdout) == 1 && fwrite(meta.data(), meta.size(), 1, stdout) == 1 &&
      fwrite(data, size, 1, stdout) == 1 && fflush(stdout) == 0;
}
int main(int argc, char **argv) {
  // Positional, strict arguments supplied only by the authenticated parent.
  if (argc != 9 || std::string(argv[1]) != "--run") {
    fprintf(stderr, "usage: --run seconds wideRoad|narrowRoad left top width height bitrate\n");
    return 2;
  }
  int seconds = std::stoi(argv[2]), left = std::stoi(argv[4]), top = std::stoi(argv[5]);
  int width = std::stoi(argv[6]), height = std::stoi(argv[7]), bitrate = std::stoi(argv[8]);
  const std::string camera(argv[3]);
  if (seconds < 0 || seconds > 60 || (camera != "wideRoad" && camera != "narrowRoad") ||
      left < 0 || top < 0 || width < 64 || height < 64 || width > 1928 || height > 1208 ||
      ((left | top | width | height) & 1) || bitrate < 500000 || bitrate > 8000000) return 2;
  if (setpriority(PRIO_PROCESS, 0, 19) != 0) return 3;
  cpu_set_t cpus; CPU_ZERO(&cpus); for (int i=0;i<4;i++) CPU_SET(i,&cpus);
  if (sched_setaffinity(0,sizeof(cpus),&cpus) != 0) return 3;
  signal(SIGPIPE,SIG_IGN);
  signal(SIGTERM,stop_worker);signal(SIGINT,stop_worker);
  VisionIpcClient client("camerad", camera == "wideRoad" ? VISION_STREAM_WIDE_ROAD : VISION_STREAM_NARROW_ROAD, true);
  if (!client.connect(false)) return 4;
  const auto &source = client.buffers[0];
  if (left+width > (int)source.width || top+height > (int)source.height) return 5;
  const size_t stride = VENUS_Y_STRIDE(COLOR_FMT_NV12, 1280);
  const size_t uv_offset = stride * VENUS_Y_SCANLINES(COLOR_FMT_NV12, 720);
  const size_t length = VENUS_BUFFER_SIZE(COLOR_FMT_NV12, 1280, 720);
  VisionBuf buffers[3]; std::deque<VisionBuf *> available;
  for (auto &buf: buffers) {
    buf.allocate(length); buf.init_yuv(1280,720,stride,uv_offset); available.push_back(&buf);
  }
  std::mutex mutex; std::condition_variable done;
  std::map<int64_t, VisionIpcBufExtra> pending;
  std::atomic<bool> failed{false}; std::atomic<int> encoded{0}; int submitted = 0;
  V4LEncoder::Options options;
  options.input_done_callback = [&](VisionBuf *buf) {
    std::lock_guard<std::mutex> guard(mutex); available.push_back(buf); done.notify_one();
  };
  options.packet_callback = [&](uint8_t *data, size_t size, int64_t pts, bool config, bool keyframe) {
    VisionIpcBufExtra extra{};
    if (!config) {
      std::lock_guard<std::mutex> guard(mutex);
      auto it = pending.find(pts);
      if (it == pending.end()) { failed = true; return; }
      extra = it->second; pending.erase(it); encoded++;
    }
    std::ostringstream meta;
    meta << "{\"version\":3,\"codec\":\"h264\",\"camera\":\"" << camera << "\",\"config\":"
         << (config ? "true" : "false") << ",\"keyframe\":" << (keyframe ? "true" : "false")
         << ",\"width\":" << 1280 << ",\"height\":" << 720 << ",\"frameId\":" << extra.frame_id
         << ",\"timestampEofNs\":" << extra.timestamp_eof << ",\"ptsUs\":" << pts
         << ",\"sentElapsedNs\":" << monotonic_ns() << ",\"sourceWidth\":" << source.width
         << ",\"sourceHeight\":" << source.height << ",\"crop\":[" << left << ',' << top << ','
         << left+width << ',' << top+height << "]}";
    if (!write_packet(meta.str(), data, size)) failed = true;
  };
  EncoderInfo info{}; info.publish_name = "ar-h264-isolated"; info.record = false;
  info.is_live = true; info.fps = 20; info.frame_width = 1280; info.frame_height = 720;
  info.get_settings = [bitrate](int) {
    return EncoderSettings{.encode_type=cereal::EncodeIndex::Type::QCAMERA_H264,.bitrate=bitrate,.gop_size=5};
  };
  V4LEncoder encoder(info,1280,720,options); encoder.encoder_open();
  const uint64_t deadline = monotonic_ns()+uint64_t(seconds)*1000000000;
  while (!failed && !stopped && (seconds==0 || monotonic_ns() < deadline)) {
    VisionIpcBufExtra extra; VisionBuf *frame=client.recv(&extra,100);
    if (!frame) continue;
    if (frame->get_frame_id()!=extra.frame_id) continue;
    if (monotonic_ns()<extra.timestamp_eof || monotonic_ns()-extra.timestamp_eof>300000000) continue;
    VisionBuf *target;
    {
      std::unique_lock<std::mutex> guard(mutex);
      if (!done.wait_for(guard,std::chrono::milliseconds(100),[&]{return !available.empty();})) {
        failed=true; break;
      }
      target=available.front(); available.pop_front();
      if (pending.size()>=4) { failed=true; break; }
      pending.emplace(extra.timestamp_eof/1000,extra);
    }
    frame->sync(VISIONBUF_SYNC_FROM_DEVICE);
    if (width == 1280 && height == 720) {
      for (int row=0;row<720;row++) memcpy(target->y+row*stride,frame->y+(top+row)*frame->stride+left,1280);
      for (int row=0;row<360;row++) memcpy(target->uv+row*stride,frame->uv+(top/2+row)*frame->stride+left,1280);
    } else if (width == 960 && height == 540) {
      const uint8_t yi[16]={0,0,1,2,3,3,4,5,6,6,7,8,9,9,10,11};
      const uint8_t ui[16]={0,1,0,1,2,3,4,5,6,7,6,7,8,9,10,11};
      const uint8x16_t yidx=vld1q_u8(yi), uidx=vld1q_u8(ui);
      for (int row=0;row<720;row++) {
        const uint8_t *src=frame->y+(top+row*3/4)*frame->stride+left;
        uint8_t *dst=target->y+row*stride;
        for (int x=0;x<1280;x+=16) vst1q_u8(dst+x,vqtbl1q_u8(vld1q_u8(src+x*3/4),yidx));
      }
      for (int row=0;row<360;row++) {
        const uint8_t *src=frame->uv+(top/2+row*3/4)*frame->stride+left;
        uint8_t *dst=target->uv+row*stride;
        for (int x=0;x<1280;x+=16) vst1q_u8(dst+x,vqtbl1q_u8(vld1q_u8(src+x*3/4),uidx));
      }
    } else {
      for (int row=0;row<720;row++) {
        const uint8_t *src=frame->y+(top+row*height/720)*frame->stride+left;
        uint8_t *dst=target->y+row*stride;
        for (int x=0;x<1280;x++) dst[x]=src[x*width/1280];
      }
      for (int row=0;row<360;row++) {
        const uint8_t *src=frame->uv+(top/2+row*(height/2)/360)*frame->stride+left;
        uint8_t *dst=target->uv+row*stride;
        for (int x=0;x<640;x++) { int s=2*(x*(width/2)/640); dst[2*x]=src[s]; dst[2*x+1]=src[s+1]; }
      }
    }
    if (frame->get_frame_id()!=extra.frame_id) {
      std::lock_guard<std::mutex> guard(mutex);
      pending.erase(extra.timestamp_eof/1000);available.push_back(target);continue;
    }
    target->sync(VISIONBUF_SYNC_TO_DEVICE);
    encoder.encode_frame(target,&extra); submitted++;
  }
  encoder.encoder_close();
  for (auto &buf:buffers) buf.free();
  fprintf(stderr,"ar_h264 submitted=%d encoded=%d failed=%d\n",submitted,encoded.load(),int(failed.load()));
  return failed ? 6 : 0;
}
