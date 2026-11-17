package com.qoder.cs.core.seed;

import com.qoder.cs.core.config.CsProperties;
import com.qoder.cs.core.subscription.SubscriptionService;
import com.qoder.cs.facade.dto.ActivationCodeDTO;
import com.qoder.cs.kb.KbService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/** 启动播种（幂等只增不删）：内置租户 + FAQ 知识库 + 首批演示激活码。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeedService {

    private final KbService kb;
    private final SubscriptionService subscriptions;
    private final CsProperties props;

    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        subscriptions.ensureBuiltinTenants();
        int added = 0;
        for (String[] faq : FAQS) {
            if (kb.seedIfAbsent(faq[0], faq[1], faq[2], faq[3])) {
                added++;
            }
        }
        if (added > 0) {
            log.info("FAQ 播种新增 {} 条（总计 {} 条）", added, kb.list().size());
        }
        if (subscriptions.listCodes(null).isEmpty()) {
            List<ActivationCodeDTO> codes = subscriptions.generateCodes(props.getPlan().getDays(), 3);
            for (ActivationCodeDTO c : codes) {
                log.info("演示激活码（{} 天）：{}", c.getDays(), c.getCode());
            }
        }
    }

    private static final String[][] FAQS = {
            {"打车怎么计价？",
                    "计价 = 起步价 + 里程费 + 时长费，再乘以高峰/夜间系数，远途加收远途费，不足最低消费按最低消费收取。不同车型（经济/舒适/商务）费率不同，可在「计价规则」中查询实时费率。",
                    "计价", "计价,价格,费用,起步价,里程,多少钱"},
            {"高峰期打车为什么更贵？",
                    "高峰时段与夜间时段会应用溢价系数（以计价规则为准），属于正常调价。下单前预估价已包含系数，实际以行程结束结算为准。",
                    "计价", "高峰,夜间,溢价,系数,贵"},
            {"订单有哪些状态？",
                    "订单状态依次为：DISPATCHING 派单中 → ACCEPTED 已接单 → ARRIVED 司机到达 → IN_PROGRESS 行程中 → COMPLETED 已完成 → PAID 已支付；另有 CANCELLED 已取消、EXPIRED 已超时、REFUNDED 已退款。",
                    "其他", "订单,状态,派单,行程"},
            {"怎么支付车费？",
                    "行程完成后可用钱包余额（BALANCE）或银行卡支付。余额不足时请先充值。支付成功后订单变为 PAID，钱包流水可查 PAY_DEBIT 记录。",
                    "支付", "支付,付款,车费,余额,银行卡"},
            {"支付失败怎么办？",
                    "请先确认钱包余额充足或银行卡信息正确，再重试支付（同一 requestId 重试不会重复扣款）。多次失败可提交工单，附上订单号与失败时间。",
                    "支付", "支付失败,扣款失败,重试"},
            {"如何申请退款？",
                    "已支付订单如对费用有异议，可提交「退款」类工单并附订单号；核实后由平台发起退款，退款金额原路退回钱包（流水 REFUND_CREDIT）。退款处理时效以工单回复为准。",
                    "退款", "退款,退钱,退费,异议"},
            {"退款多久到账？",
                    "退款审核通过后实时退回钱包余额，可在钱包流水中查询 REFUND_CREDIT 记录。若长时间未到账，请提交工单附订单号核查。",
                    "退款", "退款,到账,多久,时效"},
            {"钱包怎么充值？",
                    "在钱包页发起充值，支持银行卡等渠道，充值成功后余额实时到账（流水 TOPUP）。充值金额单位为元，系统内部按分记账。",
                    "支付", "充值,钱包,余额,TOPUP"},
            {"提现多久到账？",
                    "提现申请受理后进入处理流程，状态依次为受理/处理中/成功/失败。成功提现从钱包扣款（流水 WITHDRAW）。长时间处理中可提交工单查询。",
                    "提现", "提现,到账,取现,WITHDRAW"},
            {"提现失败了怎么办？",
                    "提现失败金额会退回钱包可用余额。请核对收款账户信息后重新发起；若余额未退回，请提交「提现」工单附提现单号。",
                    "提现", "提现失败,退回,收款账户"},
            {"为什么有冻结余额？",
                    "进行中的支付/提现会临时冻结对应金额，防止超额使用；单据完结后冻结自动释放。可用余额 = 总余额 - 冻结余额。",
                    "支付", "冻结,可用余额,余额"},
            {"取消订单收费吗？",
                    "司机接单前取消免费；接单后取消可能按平台规则收取消费，以订单详情展示为准。对取消费有异议可提交工单。",
                    "其他", "取消,取消费,收费"},
            {"怎么联系人工客服？",
                    "本系统提供智能问答与工单服务：描述问题后如无法解决，助手会生成工单草稿，您确认后即创建工单，客服处理后会在工单中回复解决方案。",
                    "其他", "人工,客服,电话,联系"},
            {"工单处理流程是什么？",
                    "工单状态：OPEN 待处理 → PROCESSING 处理中 → RESOLVED 已解决 → CLOSED 已关闭；您也可在解决前取消（CANCELLED）。已解决工单 7 天后自动关闭，期间可补充备注。",
                    "其他", "工单,流程,状态,处理"},
            {"如何订阅智能客服？",
                    "两种方式：1) 输入激活码立即开通；2) 已接入 payment-pro 的租户可用账户余额支付订阅费。套餐为标准版 30 天，续订天数自动叠加。订阅后解锁 LLM 智能问答与订单/钱包/流水查询，不限次数。",
                    "订阅", "订阅,开通,激活码,套餐,付费"},
            {"不订阅能用什么？",
                    "未订阅用户每天可免费使用 5 条 FAQ 知识库问答，工单提交与查询始终免费。订阅后解锁智能助手（LLM）与业务数据查询，且不限次数。",
                    "订阅", "免费,额度,试用,不订阅"},
            {"订阅到期了怎么办？",
                    "到期后自动回到免费模式（每日 5 条 FAQ）。可随时用激活码或余额支付续订；未到期续订会在剩余天数上叠加，不会浪费。",
                    "订阅", "到期,续订,续费,叠加"},
            {"激活码在哪里获取？",
                    "激活码由平台管理员发放（管理后台可批量生成）。每个激活码仅可使用一次，兑换后立即生效。演示环境启动日志中会打印首批演示激活码。",
                    "订阅", "激活码,获取,兑换,发放"},
            {"支付平台商户能查什么？",
                    "商户身份（MERCHANT）订阅后可查询 payment-pro 交易订单与支付单状态（trade.query）、账户余额（account.balance）。需提供 payment-pro 侧订单号/账户 ID。",
                    "支付", "商户,支付平台,对账,订单查询"},
            {"对账发现金额不一致怎么办？",
                    "请提交「支付」类工单，附商户订单号（outTradeNo）、payment-pro 订单号与差异金额。平台会按交易单+支付单+账务流水三方核对，核实后多退少补。",
                    "支付", "对账,不一致,差异,核对"},
    };
}