import 'dart:async';
import 'dart:ui' show lerpDouble;

import 'package:flutter/material.dart';

import 'native_bridge.dart';

/// One transient alert summary, as forwarded by the native listener.
///
/// SECURITY: this object exists only in Dart memory while the preview is on
/// screen. It is never written to disk and never leaves the device.
class AlertPreview {
  const AlertPreview({
    required this.packageName,
    required this.appLabel,
    required this.title,
    required this.text,
  });

  factory AlertPreview.fromMap(Map<String, dynamic> map) => AlertPreview(
        packageName: (map['packageName'] as String?) ?? '',
        appLabel: (map['appLabel'] as String?) ?? '',
        title: (map['title'] as String?) ?? '',
        text: (map['text'] as String?) ?? '',
      );

  final String packageName;
  final String appLabel;
  final String title;
  final String text;

  /// What the pill shows as its body line (matches the native overlay).
  String get summary => text.trim().isNotEmpty ? text.trim() : title.trim();
}

/// An animated, iPhone-style island pill that mirrors what the native overlay
/// (OverlayForegroundService) draws on top of every app.
///
/// The REAL always-on overlay is 100% native Kotlin (a WindowManager view) so
/// it keeps working when this app is closed. This widget is the in-app
/// equivalent: same sizes, same timings, same colors — useful as a live
/// preview/demo and as readable documentation of the island's behavior.
///
/// Behavior (identical to native):
///  - idle:     small empty black capsule (60 x 34), sitting where the
///              front camera is, like the iPhone island
///  - alert in: expands around the camera to content width (<= 320 x 56),
///              then the app icon + text fade in
///  - 3.4 s later: collapses back to the compact capsule, content cleared.
class IslandPreview extends StatefulWidget {
  const IslandPreview({super.key, this.previewStream});

  /// Overridable for tests; defaults to the live native alert stream.
  final Stream<Map<String, dynamic>>? previewStream;

  @override
  State<IslandPreview> createState() => _IslandPreviewState();
}

class _IslandPreviewState extends State<IslandPreview>
    with SingleTickerProviderStateMixin {
  static const double _compactW = 60;
  static const double _compactH = 34;
  static const double _expandedW = 320;
  static const double _expandedH = 56;
  static const Duration _expandDuration = Duration(milliseconds: 220);
  static const Duration _holdDuration = Duration(milliseconds: 3400);
  static const Duration _collapseDuration = Duration(milliseconds: 260);

  late final AnimationController _size;
  late final Animation<double> _t;
  Timer? _hideTimer;
  StreamSubscription<Map<String, dynamic>>? _subscription;
  AlertPreview? _current;

  @override
  void initState() {
    super.initState();
    _size = AnimationController(vsync: this, duration: _expandDuration)
      ..addStatusListener(_onAnimationStatus);
    _t = CurvedAnimation(
      parent: _size,
      curve: Curves.easeOutCubic,
      reverseCurve: Curves.easeInCubic,
    );
    _subscription =
        (widget.previewStream ?? NativeBridge.alertPreviews()).listen(
      _onAlert,
      onError: (Object _, StackTrace __) {
        // Ignore: in tests / before the native side is ready there is simply
        // no alert stream to listen to.
      },
    );
  }

  void _onAnimationStatus(AnimationStatus status) {
    if (status == AnimationStatus.completed) {
      _scheduleHide();
    } else if (status == AnimationStatus.dismissed) {
      // Content shown & fully collapsed: drop the in-memory preview.
      if (mounted && _current != null) {
        setState(() => _current = null);
      }
    }
  }

  void _onAlert(Map<String, dynamic> map) {
    if (!mounted) return;
    final alert = AlertPreview.fromMap(map);
    setState(() => _current = alert);
    _hideTimer?.cancel();
    if (_size.isCompleted) {
      _scheduleHide();
    } else {
      _size.duration = _expandDuration;
      _size.forward();
    }
  }

  void _scheduleHide() {
    _hideTimer?.cancel();
    _hideTimer = Timer(_holdDuration, () {
      if (!mounted) return;
      _size.duration = _collapseDuration;
      _size.reverse();
    });
  }

  @override
  void dispose() {
    _hideTimer?.cancel();
    _subscription?.cancel();
    _size.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Semantics(
      label: 'Dynamic Island preview',
      child: AnimatedBuilder(
        animation: _t,
        builder: (context, _) {
          final double value = _t.value;
          final double w =
              lerpDouble(_compactW, _expandedW, value) ?? _compactW;
          final double h =
              lerpDouble(_compactH, _expandedH, value) ?? _compactH;
          final AlertPreview? alert = _current;
          // The capsule stays empty (like the iPhone island) until it is wide
          // enough for the content; icon + text fade in together near the end
          // of the expansion and fade out as it collapses.
          final double contentOpacity =
              ((value - 0.55) / 0.45).clamp(0.0, 1.0).toDouble();

          return ClipRRect(
            borderRadius: BorderRadius.circular(24),
            child: Container(
              width: w,
              height: h,
              color: const Color(0xFF0B0B0F),
              child: alert == null
                  ? null
                  : Opacity(
                      opacity: contentOpacity,
                      child: Padding(
                        padding: const EdgeInsets.only(left: 12, right: 12),
                        child: Row(
                          children: [
                            _AppIconBadge(appLabel: alert.appLabel),
                            const SizedBox(width: 8),
                            Expanded(
                              child: Column(
                                mainAxisAlignment: MainAxisAlignment.center,
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Text(
                                    alert.appLabel,
                                    maxLines: 1,
                                    overflow: TextOverflow.ellipsis,
                                    style: const TextStyle(
                                      fontSize: 11,
                                      fontWeight: FontWeight.w500,
                                      color: Color(0xFF9E9EA7),
                                      height: 1.2,
                                    ),
                                  ),
                                  Text(
                                    alert.summary,
                                    maxLines: 1,
                                    overflow: TextOverflow.ellipsis,
                                    style: const TextStyle(
                                      fontSize: 14,
                                      fontWeight: FontWeight.w400,
                                      color: Colors.white,
                                      height: 1.25,
                                    ),
                                  ),
                                ],
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
            ),
          );
        },
      ),
    );
  }
}

/// Rounded avatar used in the Dart preview. (The native overlay shows the
/// real sender icon, which never leaves the native side.)
class _AppIconBadge extends StatelessWidget {
  const _AppIconBadge({required this.appLabel});

  final String appLabel;

  @override
  Widget build(BuildContext context) {
    const List<Color> palette = [
      Color(0xFF6C5CE7),
      Color(0xFF00B894),
      Color(0xFFE17055),
      Color(0xFF0984E3),
      Color(0xFFE84393),
      Color(0xFF00CEC9),
    ];
    final Color tint = palette[appLabel.hashCode.abs() % palette.length];
    return Container(
      width: 40,
      height: 40,
      decoration: BoxDecoration(
        color: Color.alphaBlend(
          tint.withValues(alpha: 0.22),
          const Color(0xFF1F1F28),
        ),
        borderRadius: BorderRadius.circular(10),
      ),
      alignment: Alignment.center,
      child: Text(
        appLabel.isEmpty ? '?' : appLabel.substring(0, 1).toUpperCase(),
        style: const TextStyle(
          color: Colors.white,
          fontSize: 18,
          fontWeight: FontWeight.w600,
        ),
      ),
    );
  }
}
