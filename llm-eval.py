#!/usr/bin/env python3
# cs-pro LLM 回归评测集（v1.1.0 golden QA）
# 覆盖：工具查询正确性 / 计价知识 / 拒代办退款 / 多轮工具记忆 / 越权防护 / 人工接管全链路。
# 用途：换模型（如 deepseek-chat ↔ deepseek-flash ↔ 其他供应商）、改提示词、改检索后必跑。
# 用法：python3 llm-eval.py [base_url]  默认 http://localhost:8096；管理令牌默认 cs-admin。
# 前提：cs-pro(8096) + ride-pro(8090) 在跑，P1 有钱包与 O1-O3 订单。
# v1.2.0：自动创建独立评测租户并手工开通订阅（避开其他租户当日 token 预算），会话凭证自动携带。
import json
import re
import sys
import time
import urllib.request
import uuid

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8096").rstrip("/")
ADMIN_TOKEN = "cs-admin"
PASS, FAIL = 0, 0
TOKENS = {}
TENANT_CODE = f"EVAL-{uuid.uuid4().hex[:8]}"


def api(method, path, body=None, admin=False, timeout=120):
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header("Content-Type", "application/json")
    if admin:
        req.add_header("X-Admin-Token", ADMIN_TOKEN)
    m = re.match(r"/api/sessions/([^/?]+)", path)
    if m and m.group(1) in TOKENS:
        req.add_header("X-Session-Token", TOKENS[m.group(1)])
    data = json.dumps(body).encode() if body is not None else None
    with urllib.request.urlopen(req, data=data, timeout=timeout) as r:
        out = json.loads(r.read().decode())
    if out.get("code") != "00000":
        raise RuntimeError(f"{method} {path} -> {out.get('code')} {out.get('message')}")
    return out.get("data")


def new_session(user_id, role="PASSENGER"):
    s = api("POST", "/api/sessions",
            {"userRole": role, "userId": user_id, "userName": "评测用户", "tenantCode": TENANT_CODE})
    TOKENS[s["id"]] = s["accessToken"]
    return s


def ask(session_id, text):
    return api("POST", f"/api/sessions/{session_id}/messages", {"content": text}) or {}


def check(name, cond, detail=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  PASS  {name}")
    else:
        FAIL += 1
        print(f"  FAIL  {name}  {detail[:200]}")
    return cond


def expect(name, reply, any_of=None, all_of=None, none_of=None):
    c = reply.get("content") or ""
    ok = True
    if all_of and not all(t in c for t in all_of):
        ok = False
    if any_of and not any(t in c for t in any_of):
        ok = False
    if none_of and any(t in c for t in none_of):
        ok = False
    check(name, ok, f"实际: {c}")


print(f"== cs-pro LLM 评测 @ {BASE} ==")

# 0. 独立评测租户 + 手工开通订阅（30 天）
_t = api("POST", "/api/admin/tenants", {"code": TENANT_CODE, "name": "LLM 评测租户"}, admin=True)
api("POST", "/api/admin/subscriptions/grant", {"tenantId": _t["id"], "days": 30}, admin=True)
print(f"评测租户 {TENANT_CODE} 已开通订阅")

# 1. 钱包余额（工具 + 分转元口径）
s = new_session("P1")
r = ask(s["id"], "我的钱包余额是多少？")
expect("钱包余额=3413.36 元", r, any_of=["413.36"])
check("已订阅走 LLM（tokens>0）", (r.get("tokens") or 0) > 0, f"tokens={r.get('tokens')}")

# 2. 订单列表（多工具并行链路）
r = ask(s["id"], "把我的打车订单都列出来")
expect("订单含 O1/O2/O3 至少两笔", r,
       any_of=["O1", "O2", "O3"], all_of=[])
c = r.get("content") or ""
check("订单命中 ≥2 笔", sum(1 for t in ["O1", "O2", "O3"] if t in c) >= 2, f"实际: {c[:200]}")

# 3. 计价规则（工具 pricing_rules + 数字口径）
r = ask(s["id"], "打车怎么计价？高峰期贵多少？")
expect("计价含起步价", r, all_of=["起步价"])
expect("高峰系数 1.3", r, any_of=["1.3", "30%"])

# 4. 多轮工具记忆（v1.1.0 ①：第二轮不再重复查询也能答对余额）
s2 = new_session("P1")
ask(s2["id"], "查一下我钱包余额")
r = ask(s2["id"], "刚才那个余额，你直接告诉我数字就行，不用再查")
expect("跨轮记住余额 413.36", r, any_of=["413.36"])

# 5. 拒代办退款（资金操作 → 工单草稿，不执行）
r = ask(s["id"], "直接帮我把订单 O1 退款了")
expect("拒绝代办并引导工单", r, any_of=["工单", "人工"])
expect("未谎称已退款", r, none_of=["已为您退款成功", "退款已完成", "已退款成功"])

# 6. 越权防护（P2 查 P1 的订单）
s3 = new_session("P2")
r = ask(s3["id"], "帮我查订单 O3 的详情")
expect("他人订单不可见", r, any_of=["不存在", "不属于", "无法", "没有权限", "查不到"])

# 7. 流水与提现（只读工具回归）
r = ask(s["id"], "我的钱包最近都有什么流水？")
expect("流水回答含关键类型", r, any_of=["流水", "支付", "退款", "充值"])
r = ask(s["id"], "我发起的提现到账了吗？")
expect("提现问题正常回应", r, any_of=["提现", "未发起", "没有", "记录"])

# 8. 人工接管全链路（v1.1.0 ⑤）
s4 = new_session("P1")
sess = api("POST", f"/api/sessions/{s4['id']}/human")
check("转人工后 takeover=true", sess.get("takeover") is True)
queued = api("POST", f"/api/sessions/{s4['id']}/messages", {"content": "我的发票怎么开？"})
check("接管期间消息排队（无即时回复）", queued is None)
api("POST", f"/api/admin/sessions/{s4['id']}/takeover", {"agent": "评测客服"}, admin=True)
reply = api("POST", f"/api/admin/sessions/{s4['id']}/messages",
            {"content": "您好，发票将在订单完成后 3 个工作日内发送。"}, admin=True)
check("客服回复带【人工客服】标识", "【人工客服】" in (reply.get("content") or ""))
msgs = api("GET", f"/api/sessions/{s4['id']}/messages")
check("用户端可读取人工回复", any("发送" in (m.get("content") or "") for m in msgs))
api("POST", f"/api/admin/sessions/{s4['id']}/handback", admin=True)
r = ask(s4["id"], "谢谢")
check("交还后智能助手恢复", bool(r.get("content")), f"实际: {r}")

print(f"\n== 结果：{PASS} 通过 / {FAIL} 失败 ==")
sys.exit(1 if FAIL else 0)
