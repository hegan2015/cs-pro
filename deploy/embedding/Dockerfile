FROM python:3.11-slim
WORKDIR /app
RUN pip install --no-cache-dir -i https://mirrors.aliyun.com/pypi/simple/ \
    fastapi uvicorn onnxruntime tokenizers numpy
COPY model/ /model/
RUN python - <<'EOF'
import os, socket, urllib.request
p = "/model/model.onnx"
if os.path.exists(p) and os.path.getsize(p) > 20_000_000:
    print("using pre-downloaded model.onnx", flush=True)
else:
    socket.setdefaulttimeout(60)
    url = "https://modelscope.cn/models/Xenova/bge-small-zh-v1.5/resolve/master/onnx/model_quantized.onnx"
    for attempt in range(5):
        try:
            print("downloading model_quantized.onnx attempt", attempt + 1, flush=True)
            urllib.request.urlretrieve(url, p)
            break
        except Exception as e:
            print("retry:", e, flush=True)
    else:
        raise SystemExit("model download failed")
EOF
COPY server.py .
EXPOSE 8100
CMD ["uvicorn", "server:app", "--host", "0.0.0.0", "--port", "8100"]