from pathlib import Path

from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import (
    BaseDocTemplate,
    Frame,
    KeepTogether,
    PageBreak,
    PageTemplate,
    Paragraph,
    Spacer,
    Table,
    TableStyle,
)


ROOT = Path(__file__).resolve().parents[1]
VERSION_NAME = "0.3.6"
VERSION_CODE = 28
OUTPUT = ROOT / "docs" / "AStockSelector_PRD.pdf"

pdfmetrics.registerFont(TTFont("YaHei", r"C:\Windows\Fonts\msyh.ttc"))
pdfmetrics.registerFont(TTFont("YaHeiBold", r"C:\Windows\Fonts\msyhbd.ttc"))

PAGE_WIDTH, PAGE_HEIGHT = A4
MARGIN_X = 18 * mm
MARGIN_TOP = 19 * mm
MARGIN_BOTTOM = 17 * mm
CONTENT_WIDTH = PAGE_WIDTH - 2 * MARGIN_X

INK = colors.HexColor("#17202A")
MUTED = colors.HexColor("#566573")
BLUE = colors.HexColor("#155FA0")
GREEN = colors.HexColor("#147D64")
AMBER = colors.HexColor("#C47B16")
LIGHT_BLUE = colors.HexColor("#EAF3FA")
LIGHT_GREEN = colors.HexColor("#E8F5F1")
LIGHT_AMBER = colors.HexColor("#FFF4E3")
GRID = colors.HexColor("#C8D0D8")


def paragraph(text, style):
    return Paragraph(text, style)


styles = getSampleStyleSheet()
styles.add(
    ParagraphStyle(
        name="CNBody",
        fontName="YaHei",
        fontSize=9.5,
        leading=15,
        textColor=INK,
        spaceAfter=5,
    )
)
styles.add(
    ParagraphStyle(
        name="CNTitle",
        fontName="YaHeiBold",
        fontSize=27,
        leading=35,
        alignment=TA_CENTER,
        textColor=INK,
        spaceAfter=8,
    )
)
styles.add(
    ParagraphStyle(
        name="CNSubtitle",
        fontName="YaHei",
        fontSize=12,
        leading=19,
        alignment=TA_CENTER,
        textColor=MUTED,
        spaceAfter=7,
    )
)
styles.add(
    ParagraphStyle(
        name="CNH1",
        fontName="YaHeiBold",
        fontSize=17,
        leading=23,
        textColor=BLUE,
        spaceBefore=2,
        spaceAfter=9,
    )
)
styles.add(
    ParagraphStyle(
        name="CNH2",
        fontName="YaHeiBold",
        fontSize=12,
        leading=18,
        textColor=INK,
        spaceBefore=8,
        spaceAfter=5,
    )
)
styles.add(
    ParagraphStyle(
        name="CNBullet",
        parent=styles["CNBody"],
        leftIndent=13,
        firstLineIndent=-8,
        bulletIndent=2,
        spaceAfter=3,
    )
)
styles.add(
    ParagraphStyle(
        name="CNTable",
        fontName="YaHei",
        fontSize=8.2,
        leading=12,
        textColor=INK,
    )
)
styles.add(
    ParagraphStyle(
        name="CNTableHead",
        fontName="YaHeiBold",
        fontSize=8.5,
        leading=12,
        textColor=colors.white,
        alignment=TA_CENTER,
    )
)
styles.add(
    ParagraphStyle(
        name="CNCallout",
        fontName="YaHei",
        fontSize=9.2,
        leading=15,
        textColor=INK,
        leftIndent=8,
        rightIndent=8,
        spaceBefore=5,
        spaceAfter=5,
    )
)


def page_decor(canvas, doc):
    canvas.saveState()
    canvas.setStrokeColor(GRID)
    canvas.setLineWidth(0.5)
    canvas.line(MARGIN_X, PAGE_HEIGHT - 13 * mm, PAGE_WIDTH - MARGIN_X, PAGE_HEIGHT - 13 * mm)
    canvas.setFont("YaHei", 7.5)
    canvas.setFillColor(MUTED)
    canvas.drawString(MARGIN_X, 9 * mm, f"AStockSelector 产品需求文档 · {VERSION_NAME}")
    canvas.drawRightString(PAGE_WIDTH - MARGIN_X, 9 * mm, f"第 {doc.page} 页")
    canvas.restoreState()


doc = BaseDocTemplate(
    str(OUTPUT),
    pagesize=A4,
    leftMargin=MARGIN_X,
    rightMargin=MARGIN_X,
    topMargin=MARGIN_TOP,
    bottomMargin=MARGIN_BOTTOM,
    title=f"AStockSelector 产品需求文档 {VERSION_NAME}",
    author="AStockSelector",
)
frame = Frame(
    MARGIN_X,
    MARGIN_BOTTOM,
    CONTENT_WIDTH,
    PAGE_HEIGHT - MARGIN_TOP - MARGIN_BOTTOM,
    leftPadding=0,
    rightPadding=0,
    topPadding=0,
    bottomPadding=0,
)
doc.addPageTemplates(PageTemplate(id="main", frames=[frame], onPage=page_decor))


def h1(text):
    return Paragraph(text, styles["CNH1"])


def h2(text):
    return Paragraph(text, styles["CNH2"])


def body(text):
    return Paragraph(text, styles["CNBody"])


def bullet(text):
    return Paragraph(f"• {text}", styles["CNBullet"])


def callout(text, color=LIGHT_BLUE):
    table = Table([[Paragraph(text, styles["CNCallout"])]], colWidths=[CONTENT_WIDTH])
    table.setStyle(
        TableStyle(
            [
                ("BACKGROUND", (0, 0), (-1, -1), color),
                ("BOX", (0, 0), (-1, -1), 0.6, GRID),
                ("LEFTPADDING", (0, 0), (-1, -1), 8),
                ("RIGHTPADDING", (0, 0), (-1, -1), 8),
                ("TOPPADDING", (0, 0), (-1, -1), 6),
                ("BOTTOMPADDING", (0, 0), (-1, -1), 6),
            ]
        )
    )
    return table


def data_table(headers, rows, widths, header_color=BLUE):
    data = [[paragraph(item, styles["CNTableHead"]) for item in headers]]
    for row in rows:
        data.append([paragraph(item, styles["CNTable"]) for item in row])
    table = Table(data, colWidths=widths, repeatRows=1, hAlign="LEFT")
    table.setStyle(
        TableStyle(
            [
                ("BACKGROUND", (0, 0), (-1, 0), header_color),
                ("GRID", (0, 0), (-1, -1), 0.45, GRID),
                ("VALIGN", (0, 0), (-1, -1), "TOP"),
                ("LEFTPADDING", (0, 0), (-1, -1), 6),
                ("RIGHTPADDING", (0, 0), (-1, -1), 6),
                ("TOPPADDING", (0, 0), (-1, -1), 5),
                ("BOTTOMPADDING", (0, 0), (-1, -1), 5),
                ("ROWBACKGROUNDS", (0, 1), (-1, -1), [colors.white, colors.HexColor("#F6F8FA")]),
            ]
        )
    )
    return table


story = [
    Spacer(1, 37 * mm),
    Paragraph("AStockSelector", styles["CNTitle"]),
    Paragraph("A 股日线策略选股器 · 产品需求文档", styles["CNSubtitle"]),
    Spacer(1, 7 * mm),
    callout(f"版本：{VERSION_NAME} | Android versionCode：{VERSION_CODE} | 文档日期：2026-08-20", LIGHT_GREEN),
    Spacer(1, 12 * mm),
    body("面向个人自用的纯 Android A 股日线筛选工具。App 在手机端读取公开行情、维护本地 SQLite 缓存，并按六个预设战法生成今日信号。"),
    Spacer(1, 4 * mm),
    callout("产品边界：不接入券商、不自动下单、不提供实时分时或 Level-2 行情。所有结果仅用于学习、复盘和策略研究，不构成投资建议。", LIGHT_AMBER),
    PageBreak(),
    h1("1. 产品目标与范围"),
    h2("1.1 核心目标"),
    bullet("在手机端完成沪深 A 股列表和日 K 数据更新，不依赖电脑常驻服务。"),
    bullet("优先复用当日有效缓存，只补新增、缺口和失败股票；列表异常时自动切换备用源。"),
    bullet("六个预设战法始终基于同一份未复权 K 线和统一流动性门槛。"),
    bullet("筛选结果、已选战法和参数在关闭 App 后继续保留。"),
    h2("1.2 用户主要操作"),
    data_table(
        ["操作", "预期行为", "结果"],
        [
            ["智能更新并筛选", "判断目标收盘日，检查缓存口径和日期；仅在需要时联网。", "显示当日全量筛选结果。"],
            ["切换战法", "不重新计算 K 线；直接在已保存的六战法结果中按“且”过滤。", "同股多战法合并为一条。"],
            ["调整参数", "年线附近范围按 1% 调整；最低成交额按 1000 万调整并保存。", "降低成交额门槛时，仅补新进入范围的 K 线后重筛。"],
            ["重建缓存", "二次确认后删除旧 K 线并重新下载约 270 个交易日。", "保留筛选参数和战法选择。"],
            ["检测程序更新", "读取 GitHub 更新元数据；用户确认后下载、校验并打开系统安装器。", "只安装校验通过的正式签名 APK。"],
        ],
        [31 * mm, 91 * mm, 52 * mm],
    ),
    h2("1.3 不在当前范围"),
    bullet("自定义七类指标筛选器、AI 分析、自动交易、云端账户和多设备同步。"),
    bullet("北交所主动枚举、炸板回封等依赖分时数据的精确判断。"),
    PageBreak(),
    h1("2. 行情与缓存"),
    h2("2.1 数据源规则"),
    data_table(
        ["数据", "主源", "备用源", "口径与校验"],
        [
            ["股票列表", "新浪沪深 A 股", "腾讯报价列表", "过滤 ST、退市、无成交和未知板块；主表保留完整有效股票。"],
            ["日 K", "新浪", "腾讯", "主源请求失败、空数据或日期不合格时切备用源；两者均使用未复权价格，且最新日期必须精确等于目标收盘日。"],
            ["成交额", "列表当前成交额", "列表当前成交额", "只写入最新一条 K 线；历史日 K 成交额为 0，不伪造历史值。"],
        ],
        [28 * mm, 34 * mm, 34 * mm, 78 * mm],
        GREEN,
    ),
    h2("2.2 缓存口径迁移"),
    body("metadata 使用 price_mode=bfq_v1 标识未复权缓存。旧版本缓存没有该标识，升级后第一次智能刷新会自动执行一次完整重建，防止新浪未复权与腾讯前复权数据拼接。"),
    h2("2.3 更新流程"),
    bullet("中国时间交易日 15:30 前以最近已收盘交易日为日历上限；周末先回退，实际日期按多数样本确认。"),
    bullet("本地日期落后时，先用 8 只固定样本读取少量 K 线，确认节假日和数据源实际最新日。"),
    bullet("目标交易日解析结果缓存 30 分钟；普通刷新不续期，到期后会重新探测。"),
    bullet("已有完整股票只补最近 40 个交易日；新增或不足 270 条的股票补约 270 个交易日。"),
    bullet("单股新浪数据非空但日期未达到目标收盘日时，仍会切换腾讯；主备都不合格才进入失败队列。"),
    bullet("最低成交额下调时，只补新进入门槛范围且缺少最新 K 线的股票。"),
    bullet("连续失败会提前停止请求，但已尝试和未执行股票都写入失败队列。"),
    bullet("同一股票达到当天失败上限后暂停重试；覆盖率只统计当前成交额门槛内股票。"),
    bullet("缺口检查和可筛选股票按目标交易日索引查询，不扫描全部 270 日 K 线。"),
    bullet("筛选阶段仅接受最后 K 线日期等于缓存最新日期的股票。"),
    h2("2.4 本地保留"),
    body("数据库按全市场最近 270 个不同交易日清理过早 K 线。App 禁用 Android 系统备份，行情缓存和筛选设置不进入云备份。"),
    PageBreak(),
    h1("3. 策略与筛选"),
    h2("3.1 全局硬过滤"),
    callout("非 ST；至少 260 条 K 线；最后 K 线日期等于缓存最新交易日；最新交易日成交额达到用户设置的最低门槛。", LIGHT_GREEN),
    Spacer(1, 3 * mm),
    data_table(
        ["战法", "主要规则", "判定方式"],
        [
            ["年线首板", "近涨停阈值、上穿或靠近 MA250、20 日无近涨停、放量、非一字板。", "近涨停为硬条件；其余评分，总分至少 80。"],
            ["九阳蓄势", "9 日至少 7 阳、累计涨幅不超 25%、至少 4 日靠近 MA250、年线走平向上、收盘在年线上。", "评分制，总分至少 85。"],
            ["博弈K", "前阴后阳、修复 80% 以上、阳包阴、距 MA20/60/250 不超 3%、收盘强度 80%、量比 1.2。", "全部为硬条件，通过后得分 100。"],
            ["低位启动", "靠近 120 日低点、低点稳定、30 日收敛、站上 MA20、靠近 MA60、放量阳线、接近 20 日高点。", "全部为硬条件，通过后得分 100。"],
            ["建仓三阳", "上升趋势中连续 3 至 5 根实体至少 20% 的阳线，包含非涨停光头阳及前一日 3 倍、前 5 日均量 1.5 倍的确认量。", "全部为硬条件，通过后得分 100。"],
            ["拉升三阳", "最近 3 根实体至少 20% 的阳线量价逐日提高，第三阳收盘创近 5 日新高并直接形成信号。", "全部为硬条件，通过后得分 100。"],
        ],
        [28 * mm, 104 * mm, 42 * mm],
        AMBER,
    ),
    h2("3.2 结果复用和排序"),
    bullet("每个规则版本、交易日和股票保存一次评价；零命中也是有效结果。规则升级时只清理旧评价，不删除 K 线。"),
    bullet("更新成功或尚未评价的股票才重新计算；每批 64 只使用一个 SQLite 事务保存。"),
    bullet("多个战法同时选择时使用“且”；同一股票只显示一次并汇总命中战法。"),
    bullet("相同规则下，相比上一份有效快照新增的股票置顶并显示红底白字“新”。"),
    h2("3.3 参数"),
    body("年线附近范围默认 5%，步长 1%；最低成交额默认 5000 万，步长 1000 万。参数使用 SharedPreferences 持久保存，重开 App 和重建行情缓存均不重置。"),
    PageBreak(),
    h1("4. 后台、更新与安全"),
    h2("4.1 后台行情任务"),
    bullet("用户点击后启动 dataSync 类型前台服务，通知栏显示阶段、进度和预计剩余时间。"),
    bullet("进度通知最多每秒刷新一次；阶段切换和最终状态立即更新，避免触发 Android 通知限流。"),
    bullet("任务期间持有最长 2 小时 PARTIAL_WAKE_LOCK，并提供忽略电池优化设置入口。"),
    bullet("服务使用 START_NOT_STICKY；任务状态丢失后不会按默认参数意外重新启动。"),
    callout("前台服务和电池优化豁免只能降低被清理概率，不能承诺任何 Android 厂商系统都绝不终止进程。", LIGHT_AMBER),
    h2("4.2 App 内更新"),
    bullet(f"界面只显示三段式版本号，例如 {VERSION_NAME}；versionCode 仅用于内部新旧比较。"),
    bullet("latest.json 必须包含版本、HTTPS 地址、APK SHA256、大小和更新说明。"),
    bullet("下载最多重试 3 次，中断或失败会清理 .part 和损坏 APK。"),
    bullet("元数据中的 APK 大小不得超过 256 MiB；响应长度和流式下载字节数超出预期时立即终止并清理临时文件。"),
    bullet("大小及 SHA256 通过后才打开 Android 系统安装器。"),
    h2("4.3 发布安全"),
    data_table(
        ["检查", "要求"],
        [
            ["Gradle", "固定 8.9 下载地址和官方 distributionSha256Sum。"],
            ["GitHub Actions", "所有 uses 固定为完整提交 SHA，避免可变标签漂移。"],
            ["质量门", "文档与版本检查、单元测试、Android Lint、debug 构建。"],
            ["Release", "正式签名 APK；签名证书 SHA256 必须匹配已发布证书。"],
            ["版本", "versionName 三段数字且每段小于 10；versionCode 必须单调增加。"],
        ],
        [45 * mm, 129 * mm],
        GREEN,
    ),
    PageBreak(),
    h1("5. 验收标准"),
    data_table(
        ["编号", "验收内容", "通过条件"],
        [
            ["AC-01", "主备源价格口径", "同日未复权 OHLC 一致；切换备用源后均线无复权断层。"],
            ["AC-02", "旧缓存迁移", "存在旧日 K 且缺少 bfq_v1 时，只触发一次自动重建。"],
            ["AC-03", "逐股日期", "单股最新日不等于目标收盘日时不进入今日信号，并写入失败队列。"],
            ["AC-04", "完全断网", "提前停止后，目标股票均有失败记录；界面显示可重试原因。"],
            ["AC-05", "成交额", "任一战法均不能命中低于门槛的股票；降低门槛后能补齐新增范围。"],
            ["AC-06", "零命中复用", "缓存日期和规则未变时，零命中不重新执行全库筛选。"],
            ["AC-07", "增量性能", "已评价股票直接读取结果表；新评价按批次事务写入。"],
            ["AC-08", "参数持久化", "关闭并重开 App 后保留年线范围、最低成交额和战法选择。"],
            ["AC-09", "新标记", "任务完成状态只处理一次；重开界面不清空已保存的新标记。"],
            ["AC-10", "后台恢复", "系统以空 Intent 重建服务时立即停止，不启动默认更新。"],
            ["AC-11", "更新包", "大小、SHA256 和签名任一不符时禁止继续安装或发布。"],
            ["AC-12", "隐私", "Android backup 关闭；不上传行情缓存、参数或筛选结果。"],
        ],
        [18 * mm, 55 * mm, 101 * mm],
        BLUE,
    ),
    h2("5.1 已知风险"),
    bullet("公开行情接口可能限流、延迟、缺失或变更；失败队列只能重试，不能保证第三方源持续可用。"),
    bullet("日 K 无法准确判断炸板回封等分时过程，也不能代替正式量化回测。"),
    bullet("首次安装或缓存口径升级需要全量下载，耗时取决于网络、机型和数据源响应。"),
    h2("5.2 发布前最低检查"),
    body("版本一致性、Python 脚本、单元测试、Android Lint、debug APK、release 签名指纹、APK 大小和 SHA256 必须全部通过；任何一项失败都不得发布更新元数据。"),
]

doc.build(story)
print(OUTPUT)
