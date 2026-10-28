# Local model assets

The server's default model files live here so a normal run does not depend on a
first-use Hugging Face download. The weight directories are intentionally ignored
by git.

| directory | source | pinned revision | runtime |
|---|---|---|---|
| `whisper-large-v3-turbo/` | `mlx-community/whisper-large-v3-turbo` | `a4aaeec0636e6fef84abdcbe3544cb2bf7e9f6fb` | loaded directly by `mlx-whisper` |
| `qwen3.8-9b-abliterated-mlx-4bit/` | `PocketAiHub/Qwen3.8-9B-Abliterated-MLX`, folder `4bit/` | `3ffc2d26a586a8061caf6edb0c48aee8d4777941` | source used to create the Ollama model `qwen3.5-abliterated:9b`, no longer the default: that import is blind (2026-09-24) |
| `z-image-turbo-mflux-4bit/` | `filipstrand/Z-Image-Turbo-mflux-4bit` | `b3a8f31115a11f2f9e2fa0bfbc8d78dcc3e6568b` | the default image model, loaded directly by MFLUX |
| `flux2-klein-4b-mflux-4bit/` | `Runpod/FLUX.2-klein-4B-mflux-4bit` | `7ee1b3aa8178a1240050490072196a57da2bf2a9` | loaded directly by MFLUX |

Chat is not the server's: the C1 calls Ollama by model name. That model's project-local
source is retained here for provenance and re-import; the image and speech models are
loaded from these directories directly.
