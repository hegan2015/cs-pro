#!/usr/bin/env bash
# 下载 bge-small-zh-v1.5 ONNX 模型到 model/（构建镜像前执行；模型文件不入 git）
# 源：ModelScope Xenova 镜像仓库（国内 CDN，实测 ~15MB/s）
# 用法: ./download-model.sh [fp32|fp16|int8]   默认 int8（24MB，检索质量足够）
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p model
VARIANT="${1:-int8}"
case "$VARIANT" in
  fp32) FILE="onnx/model.onnx" ;;
  fp16) FILE="onnx/model_fp16.onnx" ;;
  int8) FILE="onnx/model_quantized.onnx" ;;
  *) echo "未知变体: $VARIANT（可选 fp32/fp16/int8）"; exit 1 ;;
esac
BASE="https://modelscope.cn/models/Xenova/bge-small-zh-v1.5/resolve/master"
[ -f model/tokenizer.json ] || curl -fL --retry 3 -o model/tokenizer.json "$BASE/tokenizer.json"
curl -fL --retry 3 -C - -o model/model.onnx "$BASE/$FILE"
echo "OK: model/tokenizer.json + model/model.onnx ($VARIANT)"