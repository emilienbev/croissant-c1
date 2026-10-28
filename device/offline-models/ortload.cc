// ortload MODEL THREADS MODE OPTLEVEL [RUNS [OUT]]
// Times onnxruntime session creation for one model under the options sherpa-onnx does
// not expose, then inference on dummy input (every dynamic dim = 400, every int64 =
// 400: 4 s of features for the Parakeet encoder), so a faster load that costs speed at
// inference shows up. OPTLEVEL 0/1/2/99. MODE:
//   default     as sherpa-onnx loads it
//   noprepack   session.disable_prepacking
//   mmap        MODEL is .ort: memory-mapped, initializers used from the mapping
//   save        also write the optimised model to OUT, its initializers and the
//               pre-packed weights in external files beside it (this CPU's kernels)
//   saved       MODEL is such a saved model
// Built with the NDK against the onnxruntime-android 1.28.0 AAR (offline/README.md).
#include <onnxruntime_cxx_api.h>

#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

static double since(std::chrono::steady_clock::time_point t) {
  return std::chrono::duration<double>(std::chrono::steady_clock::now() - t).count();
}

int main(int argc, char **argv) {
  if (argc < 5) {
    fprintf(stderr, "usage: ortload MODEL THREADS MODE OPTLEVEL [RUNS [OUT]]\n");
    return 2;
  }
  const char *model = argv[1];
  const std::string mode = argv[3];
  int threads = atoi(argv[2]), opt = atoi(argv[4]);
  int runs = argc > 5 ? atoi(argv[5]) : 2;

  Ort::Env env(ORT_LOGGING_LEVEL_WARNING, "ortload");
  Ort::SessionOptions so;
  so.SetIntraOpNumThreads(threads);
  so.SetGraphOptimizationLevel(static_cast<GraphOptimizationLevel>(opt));
  if (getenv("ORTLOAD_PROFILE")) so.EnableProfiling(getenv("ORTLOAD_PROFILE"));
  if (mode == "noprepack") so.AddConfigEntry("session.disable_prepacking", "1");
  if (mode == "mmap") {
    so.AddConfigEntry("session.use_memory_mapped_ort_model", "1");
    so.AddConfigEntry("session.use_ort_model_bytes_for_initializers", "1");
  }
  if (mode == "save") {
    if (argc < 7) { fprintf(stderr, "save needs OUT\n"); return 2; }
    std::string out = argv[6];
    so.SetOptimizedModelFilePath(out.c_str());
    std::string data = out.substr(out.find_last_of('/') + 1) + ".data";
    so.AddConfigEntry("session.optimized_model_external_initializers_file_name", data.c_str());
    so.AddConfigEntry("session.optimized_model_external_initializers_min_size_in_bytes", "1024");
    so.AddConfigEntry("session.save_external_prepacked_constant_initializers", "1");
  }

  auto t0 = std::chrono::steady_clock::now();
  Ort::Session session(env, model, so);
  printf("load_s=%.3f\n", since(t0));

  Ort::AllocatorWithDefaultOptions alloc;
  auto mem = Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);
  std::vector<std::string> in_names, out_names;
  std::vector<Ort::Value> inputs;
  std::vector<std::vector<float>> fbufs;
  std::vector<std::vector<int64_t>> ibufs;
  for (size_t i = 0; i < session.GetInputCount(); ++i) {
    in_names.push_back(session.GetInputNameAllocated(i, alloc).get());
    auto info = session.GetInputTypeInfo(i).GetTensorTypeAndShapeInfo();
    auto shape = info.GetShape();
    size_t n = 1;
    for (auto &d : shape) {
      if (d < 0) d = (&d == &shape[0]) ? 1 : 400;
      n *= d;
    }
    if (info.GetElementType() == ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT) {
      fbufs.emplace_back(n, 0.01f);
      inputs.push_back(Ort::Value::CreateTensor<float>(mem, fbufs.back().data(), n, shape.data(), shape.size()));
    } else {
      ibufs.emplace_back(n, 400);
      inputs.push_back(Ort::Value::CreateTensor<int64_t>(mem, ibufs.back().data(), n, shape.data(), shape.size()));
    }
  }
  for (size_t i = 0; i < session.GetOutputCount(); ++i)
    out_names.push_back(session.GetOutputNameAllocated(i, alloc).get());
  std::vector<const char *> ic, oc;
  for (auto &s : in_names) ic.push_back(s.c_str());
  for (auto &s : out_names) oc.push_back(s.c_str());
  for (int r = 0; r < runs; ++r) {
    auto t1 = std::chrono::steady_clock::now();
    session.Run(Ort::RunOptions{nullptr}, ic.data(), inputs.data(), ic.size(), oc.data(), oc.size());
    printf("run%d_s=%.3f\n", r, since(t1));
  }
  return 0;
}
