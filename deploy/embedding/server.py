"""cs-embedding：本地 ONNX 中文向量化服务（bge-small-zh-v1.5，512 维）。
POST /embed {texts:[...]} → {vectors:[[...]], dim, model}；GET /health 探活。
CLS pooling + L2 归一化（BGE 官方口径），无 torch 依赖。
"""
import os

import numpy as np
import onnxruntime as ort
from fastapi import FastAPI
from pydantic import BaseModel
from tokenizers import Tokenizer

MODEL_DIR = os.environ.get("MODEL_DIR", "/model")
MODEL_NAME = "bge-small-zh-v1.5"
MAX_LEN = 512

tok = Tokenizer.from_file(os.path.join(MODEL_DIR, "tokenizer.json"))
tok.enable_truncation(max_length=MAX_LEN)
tok.enable_padding(pad_id=0, pad_token="[PAD]", length=None)
sess = ort.InferenceSession(
    os.path.join(MODEL_DIR, "model.onnx"),
    providers=["CPUExecutionProvider"],
)
INPUTS = {i.name for i in sess.get_inputs()}
DIM = sess.get_outputs()[0].shape[-1]

app = FastAPI(title="cs-embedding")


class EmbedRequest(BaseModel):
    texts: list[str]


@app.get("/health")
def health():
    return {"status": "ok", "model": MODEL_NAME, "dim": DIM}


@app.post("/embed")
def embed(req: EmbedRequest):
    if not req.texts:
        return {"vectors": [], "dim": DIM, "model": MODEL_NAME}
    encs = tok.encode_batch([t if t.strip() else " " for t in req.texts])
    ids = np.array([e.ids for e in encs], dtype=np.int64)
    mask = np.array([e.attention_mask for e in encs], dtype=np.int64)
    feeds = {"input_ids": ids, "attention_mask": mask}
    if "token_type_ids" in INPUTS:
        feeds["token_type_ids"] = np.zeros_like(ids)
    out = sess.run(None, feeds)[0]  # [batch, seq, hidden]
    cls = out[:, 0, :]
    norm = np.linalg.norm(cls, axis=1, keepdims=True)
    norm[norm == 0] = 1.0
    vecs = cls / norm
    return {"vectors": vecs.tolist(), "dim": DIM, "model": MODEL_NAME}