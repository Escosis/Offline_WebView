注意：本项目代码由DeeepSeek编写，可能含有各种注释以及无用代码片段，不影响最终功能和安全性。

本项目基于GeckoView 141.0.20250714153642和NanoHTTPD 2.3.1，旨在为安卓端编写可运行完全本地化的网页。由于GeckoView不支持禁用CORS，故只能牺牲加载速度使用本地服务器。

由于GeckoView并未对网页中的select元素的响应提供默认的实现，故在本地服务器（LocalWebServer）返回 HTML 文件时，拦截 text/html 类型的响应，读取 HTML 内容，并在 </body> 标签前插入一段自定义的 JavaScript 脚本。该脚本会在网页加载完成后，查找所有 <select> 元素，将它们替换成由 div 模拟的可点击下拉菜单，并完全复制原始 <select> 的计算样式，保证视觉一致性。
