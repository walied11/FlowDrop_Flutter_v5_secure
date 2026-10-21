/// إعدادات الحماية. غيّر القيم حسب رغبتك.
class SecurityConfig {
  /// منع التحميل أثناء تشغيل VPN. أدوات الاعتراض مثل Reqable وPCAPdroid تعمل عبر VPN،
  /// لكن مستخدمين عاديين كثيرين يستعملون VPN أيضًا، فضعها false إن أزعجهم ذلك.
  static const bool blockOnVpn = true;

  /// منع التحميل عند وجود وكيل (proxy) أو مصحح أخطاء أو Frida.
  static const bool blockOnProxyOrDebugger = true;
}
