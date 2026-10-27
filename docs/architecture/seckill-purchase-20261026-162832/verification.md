# 图解交付验证记录

仅验证图解及源码引用，未运行真实业务依赖联调。规范与浏览器结果来自最终文件绑定的回执；截图采集与人工式图像审阅单独记录。

## 最终版本汇总

| 图解 | 类型 | 规范 | 浏览器证据 | 截图复核 |
|---|---|---|---|---|
| [受理流程](acceptance/acceptance.html) | workflow | showcase 9/9，0 错误，0 警告 | passed | 已查看所列深浅主题截图 |
| [异步建单流程](async-order/async-order.html) | workflow | showcase 9/9，0 错误，0 警告 | passed | 已查看所列深浅主题截图 |
| [成功时序](success-sequence/success-sequence.html) | sequence | showcase 9/9，0 错误，0 警告 | passed | 已查看所列深浅主题截图 |
| [超时恢复时序](timeout-recovery/timeout-recovery.html) | sequence | showcase 9/9，0 错误，0 警告 | passed | 已查看所列深浅主题截图 |

自动浏览器检查测量了浅色主题 1440×900、1600×1000、1920×1080、2048×1320，并检查两端尺寸的深色主题、READ／Still 状态、文字可读性与横向溢出。长图通过页面纵向滚动阅读，未通过裁切隐藏内容。

截图采集覆盖 1440×900 与 2048×1320 的浅色／深色主题。本次逐图查看浅色 1440×900 和深色 2048×1320 截图，流程图最后一次图例修改后重新生成并查看深色 2048×1320 的最终截图。查看了主线、失败支路、列宽、文字与连线，未发现相交或遮挡缺陷。截图是对应视口的首屏，时序图下半部分需页面滚动，未把首屏截图说成整页导出。自动检查和截图审阅不等于生产环境业务正确性证明。

流程图的图例审阅触发一次语义修正：把通用的 Agent／工作区名称改为本项目业务角色。时序图按工具建议仅调整列宽为 spread，保留全部消息、顺序与源码证据。下列 SHA-256 均重新计算并核对最终文件、交付回执和截图回执一致。

## 受理流程

- 输出：[acceptance.html](acceptance/acceptance.html)
- 规格：[`candidate.json`](acceptance/candidate.json)
- 最终规范／交付／浏览器回执：[acceptance.finalize.json](acceptance/review-legend/acceptance.finalize.json)
- 自动浏览器测量回执：[acceptance.browser-check.json](acceptance/review-legend/acceptance.browser-check.json)
- 截图采集回执：[acceptance.visual-check.json](acceptance/visual-review-final/acceptance.visual-check.json)
- 截图联系页：[acceptance.visual-check.html](acceptance/visual-review-final/acceptance.visual-check.html)
- specification_sha256：`e03204a1ec498f3db861d54b24194f17fec0fb0939dfed06765f282ed94c7cad`
- artifact_sha256：`f6177d1e743b2191b5ea7820f691522ef048bf2c8c6ca1014b2a0b980765a9d2`
- validation：showcase 9/9；0 errors；0 warnings
- browser_evidence：passed
- captures：passed
- visual_review：passed（所列截图范围）
- correction_rounds：1

## 异步建单流程

- 输出：[async-order.html](async-order/async-order.html)
- 规格：[`candidate.json`](async-order/candidate.json)
- 最终规范／交付／浏览器回执：[async-order.finalize.json](async-order/review-legend/async-order.finalize.json)
- 自动浏览器测量回执：[async-order.browser-check.json](async-order/review-legend/async-order.browser-check.json)
- 截图采集回执：[async-order.visual-check.json](async-order/visual-review-final/async-order.visual-check.json)
- 截图联系页：[async-order.visual-check.html](async-order/visual-review-final/async-order.visual-check.html)
- specification_sha256：`1a915143c3099a82a990a00cff31c6080f8e242946233fe20bb33e4effbaa4db`
- artifact_sha256：`a273883a293196b8e2d9e53aebd9c1b9ed6972947f3b56a741de62de6f828534`
- validation：showcase 9/9；0 errors；0 warnings
- browser_evidence：passed
- captures：passed
- visual_review：passed（所列截图范围）
- correction_rounds：1

## 成功时序

- 输出：[success-sequence.html](success-sequence/success-sequence.html)
- 规格：[`candidate.json`](success-sequence/candidate.json)
- 最终规范／交付／浏览器回执：[success-sequence.finalize.json](success-sequence/width-review/success-sequence.finalize.json)
- 自动浏览器测量回执：[success-sequence.browser-check.json](success-sequence/width-review/success-sequence.browser-check.json)
- 截图采集回执：[success-sequence.visual-check.json](success-sequence/visual-check/success-sequence.visual-check.json)
- 截图联系页：[success-sequence.visual-check.html](success-sequence/visual-check/success-sequence.visual-check.html)
- specification_sha256：`1044e7387014a62cfc6bf3d6fc7887b16ff786f9fc0b2b198ac89fa45a237cc7`
- artifact_sha256：`53ebb519b20d17f77ce7d8f9ba80629b3fbd9550b8cf1982ade5561213d24170`
- validation：showcase 9/9；0 errors；0 warnings
- browser_evidence：passed
- captures：passed
- visual_review：passed（所列截图范围）
- correction_rounds：0

## 超时恢复时序

- 输出：[timeout-recovery.html](timeout-recovery/timeout-recovery.html)
- 规格：[`candidate.json`](timeout-recovery/candidate.json)
- 最终规范／交付／浏览器回执：[timeout-recovery.finalize.json](timeout-recovery/width-review/timeout-recovery.finalize.json)
- 自动浏览器测量回执：[timeout-recovery.browser-check.json](timeout-recovery/width-review/timeout-recovery.browser-check.json)
- 截图采集回执：[timeout-recovery.visual-check.json](timeout-recovery/visual-check/timeout-recovery.visual-check.json)
- 截图联系页：[timeout-recovery.visual-check.html](timeout-recovery/visual-check/timeout-recovery.visual-check.html)
- specification_sha256：`f8e9390082b94a7c87fc7e507313ed02b099e5cbcdfda6cecb6bc7af74948e92`
- artifact_sha256：`4ea0df8d7b681d035c8e18419f41a966d17ebb8e450a97057bb564e02bbae21a`
- validation：showcase 9/9；0 errors；0 warnings
- browser_evidence：passed
- captures：passed
- visual_review：passed（所列截图范围）
- correction_rounds：0
