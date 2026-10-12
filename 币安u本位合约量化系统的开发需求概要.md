币安u本位合约量化系统的开发
后台服务端：https://gitee.com/dromara/RuoYi-Vue-Plus
后台管理前端：https://gitee.com/JavaLionLi/plus-ui
你的理解学习任务如下：
读取本项目，整体项目都基于这个项目进行开发
1、读取目录下.codex中的服务端的开发skills；
2、读取目录下ruoyi-ui/.codex中后台管理页面开发的skills；
3、读取目录下llms-full.txt对于币安api的接口；
4、学习在 https://gitee.com/dromara/RuoYi-Vue-Plus/tree/6.X/ 和 https://gitee.com/JavaLionLi/plus-ui 的框架下的开发说明；
5、学习这个文档，使用[binance/binance-connector-java: Simple Java connector to Binance Spot API](https://github.com/binance/binance-connector-java)来开发u本位合约的量化相关接口，使用的版本为12.0.3，依赖如下：
<dependency>
    <groupId>io.github.binance</groupId>
    <artifactId>binance-derivatives-trading-usds-futures</artifactId>
    <version>12.1.0</version>
</dependency>

币安数据的获取都使用以下依赖的api接口
<dependency>
    <groupId>io.github.binance</groupId>
    <artifactId>binance-derivatives-trading-usds-futures</artifactId>
    <version>12.1.0</version>
</dependency>


开发任务：在ruoyi-modules中添加ruoyi-quant模块，所有量化相关的内容都在这个模块中开发；
开发需求：
主要实现的功能：凭证管理、账户资金、交易终端、当前持仓、当前委托、成交记录、策略管理、策略日志、交易对管理；
凭证管理：客户凭证管理，添加客服凭证信息（主要是币安api凭证），测试凭证连接，设置这个客户属于哪个账号，登录账号可以看到这个账户，拥有哪些客户的权限，超级管理员拥有所有用户的权限，页面设计如下图：![客户凭证.png](%E5%AE%A2%E6%88%B7%E5%87%AD%E8%AF%81.png) ，筛选条件为客户名称，所属用户，状态。
展示列为：序号 、账号名称 、所属用户 、API Key(脱敏)、签名方式 、状态 、最后连接时间、创建时间、操作（测试连接、编辑、删除），参考图片![客户凭证添加.png](%E5%AE%A2%E6%88%B7%E5%87%AD%E8%AF%81%E6%B7%BB%E5%8A%A0.png)。
账户资金：主要显示所属用户的账户信息，比如 账户余额、可用余额、占用保证金、未实现盈亏、已实现盈亏、今日盈亏、累计盈亏、今日收益率、累计收益率、资金曲线、仓位模式、保证金模式。
交易终端：交易终端主要显示实时K线图、当前持仓、当前委托、成交记录和策略列表，K线图主要根据选择的交易对、时间周期和指标显示实时K线数据，如图：![img_3.png](K线.png)，当前持仓、当前委托和成交记录可以放在一个板块中分tab来显示，可以直接使用已有的内容做成插件调用；策略列表是可以选择已经配置好的策略直接启动或者停止。
当前持仓：主要显示列：客户名称、交易对、方向、持仓数量、开仓均价、标记价、未实现盈亏、收益率、杠杆、保证金、强平价、止盈价、止损价，筛选条件为客户名称、交易对，通过币安api实时获取；
当前委托：主要显示列：客户名称、交易对、方向、持仓数量、开仓均价、委托价格、委托时间、操作（取消委托），筛选条件为客户名称、交易对，通过币安api实时获取；
成交记录：主要显示列：客户名称、开仓交易对 、开仓方向、开仓价、开仓金额、杠杆、开仓时间、平仓价、已实现盈亏、手续费、平仓时间，筛选条件为客户名称、所属用户、交易对、开仓方向和开仓时间，通过api接口进行数据同步；
策略管理：主要是添加策略和配置策略信息以及策略授权，这个策略可以给哪个账号使用，可以参考下图：![img_1.png](策略编辑交易对2.png) ![img_2.png](策略编辑交易对1.png)；
交易对管理：主要是对交易对进行管理，同步币安上所有的u本为合约的交易对，显示如下图：![img_4.png](交易对.png)，可以手动进行同步，也可以手动进行添加。
运行监控
- 账户连接状态
- 策略运行数
- 当前持仓、委托和异常数量
- 今日收益、累计收益、资金曲线
- WebSocket 延迟、最后同步时间
- 硬止损失败、账户不一致、连接断开告警
风控配置：
- 实盘开关
  最大单笔名义价值
  单日最大亏损
  最大同时持仓
  最大下单频率
  API 权限校验
  WebSocket 断线策略
  仓位不一致处理方式

K线图使用的是"klinecharts": "^10.0.0",
K线的时间周期我已经设置好了，直接使用数据字典的b_kline_period

注意：
1、设计出相关的数据库，生成对应的sql；
2、策略模块，一定要设计为通用型，方便后期添加其他策略，最好是能动态加载策略；
3、需要实时显示的地方一定要实时显示，最好是通过socket或者see方式；
4、定时任务，自行判断是否通过已有的ruoyi-job来实现；
5、把开仓、平仓做统一方法，每个策略开平仓都调用统一方法，开平仓时要通过邮件进行通知，需要配置通知的邮箱地址，同时也要通知到相应的管理端页面，同时还要做开平仓的日志记录，日志包含最基本的 客户名称、开仓价（成交价）、开仓数量（开仓币数量和u数量）、交易对、方向、开仓策略、开仓原因（开仓满足的条件，开仓原因一定要写清楚）、订单id和开仓时间，开仓平仓后，后端管理的账户信息、当前持仓、当前委托和成交记录等记录信息也要同步更新。

第一步开发：凭证管理、交易对管理、风控配置；
第二步开发：账户资金、当前持仓、当前委托、成交记录；
第三步开发：交易终端；
第四步开发：策略管理（实现下面的两个策略）、策略日志；
第五步开发：运行监控；

策略一：
硬止损（开单后在币安上挂载）：StopLossRate（采用策略属性中的StopLossRate,策略表中的stop_loss_ratio字段)20%，这个不需要在params中配置；
止损为atr的0.5倍，开多：止损价=开仓价-0.5*atr，开空：开多：止损价=开仓价+0.5*atr
首次止盈只平总仓位的50%
首次止盈价=开仓价+1*atr
剩余阶梯式止盈： 当(开仓价+n*atr) < 实时价 < (开仓价+(n+1)*atr)时，实时价回撤到(开仓价+(n-1)*atr)平剩余
且每个账户每个交易对在同一根K线上只能开一次仓，同一根K线不能重复开仓

策略二：
硬止损：HardStopLossRate，这个不需要在params中配置； 
首次平仓比（总仓位的百分比）：FirstClosePositionRate 50%，在params中配置； 
首次止盈率回报率：FirstProfitTakingRate 10%， 在params中配置；
本地止损率：StepRatio 5%，在params中配置；
阶梯收益回报率：StepRatio 10%，在params中配置； 
止损为回报率大于-StopLossRate 首次止盈只平总仓位的FirstClosePositionRate 首次止盈回报率=FirstProfitTakingRate ；
剩余 当(开仓价*(1+StepRatio*n)) < 实时价 < (开仓价*(1+StepRatio*(n+1))时，实时价回撤到(开仓价*(1+StepRatio*(n-1))平剩余，这个其实类似阶梯式追踪止盈；
且每个账户每个交易对在同一根K线上只能开一次仓，同一根K线不能重复开仓。

以上策略是基于实时价进行开仓平仓。

根据以上文档需求说明，安装所需要的技能和插件，然后设计出详细的合约量化需求说明，如果缺少什么功能请自行补充。

写出详细的需求设计，给出相关的全部原型，经审核确认后再进行代码的开发实现，还有给出详细的开发计划，按照开发计划实施开发任务。

现在先将所有生成的代码进行格式化，后台页面的布局风格和已有页面的整体风格保持一致，可以参考demo或者通知公告的页面，后续开发的代码也完全看着这个标准进行开发。
不用管任务之前项目的其他状态，也不用恢复之前的任何信息，以当前工作目录为准进行修改，开始未完成的工作

用户（可选多个）
交易对（可选多个）
开仓方式：逐仓
最大开仓数量：2
开仓周期：15分钟
开仓比例：10%
开仓杠杆：10
止损比例：5%

阶梯：
开仓度数：   entryAngleDegrees   60  在params中配置
首次止盈率：  firstProfitTakingRate 10%  在params中配置
首次平仓比例：  firstClosePositionRate 50% 在params中配置
阶梯比例：    stepRatio 10%              在params中配置
止损比率：       stopLossRate 5%       在params中配置

{
"entryAngleDegrees": 60.0,
"firstProfitTakingRate": 0.10,
"firstClosePositionRate": 0.50,
"stepRatio": 0.10,
"stopLossRatio": 0.05
}


现在要求所有的策略都要配置共同的参数，参数如下：
策略用户（配置的账户，可多选）、
交易对（从交易对中选择，可多选）、
开仓方式（默认逐仓）：逐仓、全仓、
最大开仓数量（填写）：2、
开仓周期（从数据字典quant_kline_period  中选择）：15分钟、
开仓比例（填写）：10%、
开仓杠杆（填写）：10、
止损比例（填写）：5%，
然后每个策略在配置各自个性化的参数params，最好是可以根据策略生成一个params的例子，例如当前已有的策略kline_slope_rate ，他的参数如下：
开仓度数：   entryAngleDegrees   60  在params中配置、
首次止盈率：  firstProfitTakingRate 10%  在params中配置、
首次平仓比例：  firstClosePositionRate 50% 在params中配置、
阶梯比例：    stepRatio 10%              在params中配置、
止损比率：       stopLossRate 5%       在params中配置，他的单数params是：
{
"entryAngleDegrees": 60.0,
"firstProfitTakingRate": 0.10,
"firstClosePositionRate": 0.50,
"stepRatio": 0.10,
"stopLossRatio": 0.05
}
在插件目录中显示这个参数的例子，在最终运行时配置以上是所有的参数。
你仔细分析以上要求是否合理，是否可以实现，如果合理且可以实现，怎么实现的方案最优，给出最优最合理的方案。


添加好的策略需要授权可以使用该策略的用户权限，只用具有这个策略权限的用户才可以看到和使用这个策略