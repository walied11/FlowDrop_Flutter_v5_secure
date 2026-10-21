import 'package:flutter/foundation.dart' show kReleaseMode;
import '../core/security_config.dart';
import 'native_bridge.dart';

/// حارس الشبكة: يمنع أي اتصال أثناء وجود VPN أو وكيل أو مصحح أخطاء أو Frida
/// (في نسخة release فقط)، فلا تلتقط أدوات الفحص أي عنوان من التطبيق.
class Guard {
  static Future<bool> blocked() async {
    if (!kReleaseMode) return false;
    final risks = await NativeBridge.instance.securityRisks();
    return risks.any((r) =>
        (r == 'vpn' && SecurityConfig.blockOnVpn) ||
        (r != 'vpn' && SecurityConfig.blockOnProxyOrDebugger));
  }
}
