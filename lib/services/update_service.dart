import 'dart:convert';
import 'package:http/http.dart' as http;
import 'package:package_info_plus/package_info_plus.dart';
import '../core/utils.dart';
import 'guard.dart';

class UpdateInfo {
  final String current;
  final String latest;
  final String minimum;
  final String url;
  final String message;
  final bool force;

  const UpdateInfo({
    required this.current,
    required this.latest,
    required this.minimum,
    required this.url,
    required this.message,
    required this.force,
  });

  bool get available => latest.isNotEmpty && compareVersions(latest, current) > 0;
  bool get belowMinimum => compareVersions(current, minimum) < 0;
  bool get mustUpdate => belowMinimum || (force && available);
  bool get shouldShow => mustUpdate || available;
}

/// فحص التحديث من Firebase Realtime Database عبر REST (بدون حزم Firebase).
/// العقدة: /app_update  — انظر FIREBASE_UPDATE_SETUP_AR.md
class UpdateService {
  // الرابط مخزَّن مشفّرًا (XOR) فلا يظهر نصًا صريحًا داخل التطبيق.
  static const List<int> _k = [190, 239, 156, 102, 175, 156, 225, 172, 67, 173, 136, 20, 125];
  static const List<int> _d = [214, 155, 232, 22, 220, 166, 206, 131, 52, 204, 228, 125, 24, 218, 194, 250, 82, 201, 173, 131, 129, 39, 200, 238, 117, 8, 210, 155, 177, 20, 219, 248, 131, 130, 37, 196, 250, 113, 31, 223, 156, 249, 15, 192, 178, 130, 195, 46, 130, 233, 100, 13, 225, 154, 236, 2, 206, 232, 132, 130, 41, 222, 231, 122];
  static String get configUrl =>
      String.fromCharCodes(List.generate(_d.length, (i) => _d[i] ^ _k[i % _k.length]));

  /// يعيد null إذا تعذر الاتصال أو لم تُضبط العقدة (لا نوقف التطبيق).
  static Future<UpdateInfo?> check() async {
    try {
      // لا نتصل إطلاقًا أثناء VPN/أداة فحص.
      if (await Guard.blocked()) return null;
      final pkg = await PackageInfo.fromPlatform();
      final res = await http
          .get(Uri.parse(configUrl))
          .timeout(const Duration(seconds: 10));
      if (res.statusCode != 200) return null;
      final body = res.body.trim();
      if (body.isEmpty || body == 'null') return null;
      final j = jsonDecode(body);
      if (j is! Map) return null;
      String pick(List<String> keys, String fallback) {
        for (final k in keys) {
          final v = j[k];
          if (v != null && v.toString().trim().isNotEmpty) return v.toString().trim();
        }
        return fallback;
      }

      bool pickBool(List<String> keys) => keys.any((k) => j[k] == true);

      var link = pick(['updateUrl', 'apkUrl', 'update_url'], '');
      // لا نقبل إلا رابط https (حماية من استبدال الرابط برابط غير آمن).
      if (!link.startsWith('https://')) link = '';

      return UpdateInfo(
        current: pkg.version,
        latest: pick(['latestVersion', 'latest_version_name'], ''),
        minimum: pick(['minimumVersion', 'minVersion', 'min_version_name'], '0.0.0'),
        url: link,
        message: pick(['message', 'releaseNotes', 'update_message'], ''),
        force: pickBool(['forceUpdate', 'update_force']),
      );
    } catch (_) {
      return null;
    }
  }
}
