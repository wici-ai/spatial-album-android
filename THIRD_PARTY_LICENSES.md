# Third-Party AI Model and Backend License Inventory

Draft for legal review only. This is not legal advice. This inventory reflects
the upstream terms identified as of this commit and focuses on the AI models,
model weights, and backend rendering components used by the Spatial Album
product/backend. It is not a complete dependency license report for every
Android, Python, Gradle, or transitive package.

## Product Posture

Spatial Album's WiCi-authored app/source may be distributed under the MIT
License in `LICENSE`, but the product relies on third-party AI models with
non-commercial and research/evaluation restrictions. The conservative operating
posture is:

**Spatial Album is a non-commercial research/evaluation/demo product unless and
until all restricted upstream model components are replaced or separately
commercially licensed.**

## Inventory

| Component | Spatial Album use | Upstream license / terms | Key restriction to carry forward |
| --- | --- | --- | --- |
| Apple SHARP (`apple/ml-sharp` code) | Single-image-to-3DGS backend reconstruction code path. | Apple software license in [`apple/ml-sharp` `LICENSE`](https://github.com/apple/ml-sharp/blob/main/LICENSE). | Source redistribution is permitted subject to preserving notices. No Apple trademark endorsement; no patent or other rights beyond the express copyright license. |
| Apple SHARP model weights (`sharp_2572gikvuh.pt`; `apple/ml-sharp`) | Backend model checkpoint that predicts Gaussian splats from an input photo. | Apple Machine Learning Research Model License Agreement in [`LICENSE_MODEL`](https://github.com/apple/ml-sharp/blob/main/LICENSE_MODEL). | Research purposes only. The license defines research purposes as non-commercial scientific research / academic development and excludes commercial exploitation, product development, or use in a commercial product or service. Redistribution requires the agreement and attribution notice; derivatives remain research-only. |
| NVIDIA Difix / Difix3D (`nvidia/difix_ref`; `nv-tlabs/Difix3D`) | Generate pipeline refinement step before FLUX fill. | NVIDIA License in [`nv-tlabs/Difix3D` `LICENSE.txt`](https://github.com/nv-tlabs/Difix3D/blob/main/LICENSE.txt); Hugging Face model card for [`nvidia/difix_ref`](https://huggingface.co/nvidia/difix_ref). | Non-commercial only. The NVIDIA license limits the Work and derivatives to non-commercial research or evaluation. The `nvidia/difix_ref` model card states the model is for research and development / non-commercial use only. Preserve license/notices on redistribution; no NVIDIA trademark endorsement. |
| Stability AI SD-Turbo materials referenced by Difix3D | Upstream base/license terms bundled in Difix3D's license file. | Stability AI Community License Agreement is included after the NVIDIA terms in [`nv-tlabs/Difix3D` `LICENSE.txt`](https://github.com/nv-tlabs/Difix3D/blob/main/LICENSE.txt). | Difix inherits additional upstream Stability AI terms and acceptable-use obligations. Because the Difix release itself is under NVIDIA non-commercial terms, Spatial Album should treat Difix use as non-commercial research/evaluation only even if some Stability terms separately allow limited commercial use under conditions. |
| Black Forest Labs FLUX.1 Fill [dev] (`black-forest-labs/FLUX.1-Fill-dev`) | Peripheral image completion / outpainting backend step. | FLUX.1 [dev] Non-Commercial License v1.1.1 in the [`black-forest-labs/flux` model licenses](https://github.com/black-forest-labs/flux/blob/main/model_licenses/LICENSE-FLUX1-dev); Hugging Face model card for [`FLUX.1-Fill-dev`](https://huggingface.co/black-forest-labs/FLUX.1-Fill-dev). | Model access, use, derivatives, and distribution are limited to non-commercial purposes and non-production use. The license expressly excludes revenue-generating, production, commercial, surveillance, biometric processing, military, unlawful, and certain high-risk uses. Distribution requires the BFL attribution notice and license copy. Outputs are not model derivatives and BFL claims no ownership in outputs, but the act of operating the model in Spatial Album remains non-commercial/non-production unless separately licensed. |
| FLUX text encoders (`comfyanonymous/flux_text_encoders`: `clip_l.safetensors`, `t5xxl_fp8_e4m3fn.safetensors`) | Text/prompt embedding support for the FLUX endpoint. | The [`comfyanonymous/flux_text_encoders`](https://huggingface.co/comfyanonymous/flux_text_encoders) repository declares Apache-2.0. The T5 lineage is also distributed under Apache-2.0 in the [`google/t5-v1_1-xxl`](https://huggingface.co/google/t5-v1_1-xxl) model card; OpenAI CLIP source is MIT in [`openai/CLIP`](https://github.com/openai/CLIP/blob/main/LICENSE), with model-card deployment cautions. | Preserve applicable notices. No separate non-commercial license was identified for this text-encoder bundle, but its Spatial Album use is tied to the FLUX.1 [dev] path, so the overall FLUX-backed feature remains non-commercial/non-production unless separately licensed. |
| FLUX.1-Turbo-Alpha LoRA (`alimama-creative/FLUX.1-Turbo-Alpha`) | Optional/experimental FLUX speedup path present in backend code; not the default production path. | Hugging Face lists [`alimama-creative/FLUX.1-Turbo-Alpha`](https://huggingface.co/alimama-creative/FLUX.1-Turbo-Alpha) under `flux-1-dev-non-commercial-license`; it is trained based on FLUX.1-dev. | Treat the LoRA under the same FLUX.1 [dev] Non-Commercial License restrictions as FLUX.1-dev derivatives. Do not enable it for commercial/production use without separate rights. |
| gsplat (`nerfstudio-project/gsplat`) | Backend Gaussian-splat rendering for orbit previews and WebP previews. | Apache License 2.0 in [`nerfstudio-project/gsplat` `LICENSE`](https://github.com/nerfstudio-project/gsplat/blob/main/LICENSE). | Permissive Apache-2.0 terms: preserve copyright/license notices and comply with patent/license conditions. This component does not impose a non-commercial restriction. |

## Notes and Open Legal Review Items

- The public Android repository does not include all backend source, model
  checkpoints, or third-party weight files. This inventory was prepared from the
  current Spatial Album backend service code, the `spatial-album-backend`
  deploy/third-party tree, and upstream public license sources.
- The top-level `LICENSE` intentionally separates WiCi-authored source licensing
  from rights needed to operate the model-backed product.
- Before commercial launch, obtain written commercial rights or replacements for
  SHARP, Difix/Difix3D, and FLUX.1 Fill [dev], and re-review any model outputs,
  content-filtering, attribution, export-control, privacy, and AI-disclosure
  obligations.
