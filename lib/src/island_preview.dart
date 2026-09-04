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
///  - idle:     small empty pure-black capsule (60 x 34), sitting where the
///              front camera is so it blends with the punch hole
///  - alert in: expands around the camera (<= 300 wide, height grows with the
///              body up to 4 compact lines, capped at 160), then the app
///              icon + text fade in; ongoing notifications (e.g. a minimized
///              music player) are mirrored too
///  - after a few seconds (longer for longer text): collapses back to the
///              compact capsule, content cleared.
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
  static const double _expandedW = 300;
  static const double _minExpandedH = 56;
  static const double _maxExpandedH = 160;
  static const int _maxBodyLines = 4;
  static const Duration _expandDuration = Duration(milliseconds: 220);
  static const Duration _baseHoldDuration = Duration(milliseconds: 3400);
  static const Duration _collapseDuration = Duration(milliseconds: 260);

  static const TextStyle _labelStyle = TextStyle(
    fontSize: 11,
    fontWeight: FontWeight.w500,
    color: Color(0xFF9E9EA7),
    height: 1.2,
  );
  static const TextStyle _bodyStyle = TextStyle(
    fontSize: 13,
    fontWeight: FontWeight.w400,
    color: Colors.white,
    height: 1.25,
  );

  late final AnimationController _size;
  late final Animation<double> _t;
  Timer? _hideTimer;
  StreamSubscription<Map<String, dynamic>>? _subscription;
  AlertPreview? _current;
  double _targetExpandedH = _minExpandedH;
  Duration _hold = _baseHoldDuration;

  @override
  void initState() {
    super.initState();
    _size = AnimationController(vsync: this, duration: _expandDuration)
      ..addStatusListener(_onAnimationStatus);
    // Spring-like: slight overshoot on expand (iOS-island feel), clean
    // acceleration on collapse. The native overlay uses the matching
    // PathInterpolator(0.34, 1.56, 0.64, 1).
    _t = CurvedAnimation(
      parent: _size,
      curve: Curves.easeOutBack,
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
    setState(() {
      _current = alert;
      _targetExpandedH = _expandedHeightFor(alert);
      // Longer notifications stay on screen longer so they can be read.
      _hold = _baseHoldDuration +
          Duration(
            milliseconds: (alert.summary.length * 8).clamp(0, 4000),
          );
    });
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
    _hideTimer = Timer(_hold, () {
      if (!mounted) return;
      _size.duration = _collapseDuration;
      _size.reverse();
    });
  }

  /// Mirrors the native overlay's content measurement: height that fits the
  /// label row plus the (multi-line) body, clamped to the pill's bounds.
  double _expandedHeightFor(AlertPreview alert) {
    const double contentWidth = _expandedW - 20 - 32 - 8;
    final label = TextPainter(
      text: TextSpan(text: alert.appLabel, style: _labelStyle),
      maxLines: 1,
      textDirection: TextDirection.ltr,
    )..layout(maxWidth: contentWidth);
    final body = TextPainter(
      text: TextSpan(text: alert.summary, style: _bodyStyle),
      maxLines: _maxBodyLines,
      textDirection: TextDirection.ltr,
    )..layout(maxWidth: contentWidth);
    final double h = label.height + body.height + 20;
    return h.clamp(_minExpandedH, _maxExpandedH).toDouble();
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
      label: 'Isle preview',
      child: AnimatedBuilder(
        animation: _t,
        builder: (context, _) {
          final double value = _t.value;
          final double w =
              lerpDouble(_compactW, _expandedW, value) ?? _compactW;
          final double h =
              lerpDouble(_compactH, _targetExpandedH, value) ?? _compactH;
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
              color: const Color(0xFF000000),
              child: alert == null
                  ? null
                  : Opacity(
                      opacity: contentOpacity,
                      child: Padding(
                        padding: const EdgeInsets.only(left: 10, right: 10),
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
                                    style: _labelStyle,
                                  ),
                                  Text(
                                    alert.summary,
                                    maxLines: _maxBodyLines,
                                    overflow: TextOverflow.ellipsis,
                                    style: _bodyStyle,
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
      width: 32,
      height: 32,
      decoration: BoxDecoration(
        color: Color.alphaBlend(
          tint.withValues(alpha: 0.22),
          const Color(0xFF17171C),
        ),
        borderRadius: BorderRadius.circular(8),
      ),
      alignment: Alignment.center,
      child: Text(
        appLabel.isEmpty ? '?' : appLabel.substring(0, 1).toUpperCase(),
        style: const TextStyle(
          color: Colors.white,
          fontSize: 15,
          fontWeight: FontWeight.w600,
        ),
      ),
    );
  }
}
