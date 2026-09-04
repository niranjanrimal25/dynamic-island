import 'dart:async';
import 'dart:math' as math;
import 'dart:typed_data';
import 'dart:ui' show lerpDouble;

import 'package:flutter/material.dart';

import 'native_bridge.dart';

/// One transient alert summary, as forwarded by the native listener.
///
/// SECURITY: this object exists only in Dart memory while the preview is on
/// screen. It is never written to disk and never leaves the device.
class AlertPreview {
  const AlertPreview({
    this.key = '',
    required this.packageName,
    required this.appLabel,
    required this.title,
    required this.text,
  });

  factory AlertPreview.fromMap(Map<String, dynamic> map) => AlertPreview(
        key: (map['key'] as String?) ?? '',
        packageName: (map['packageName'] as String?) ?? '',
        appLabel: (map['appLabel'] as String?) ?? '',
        title: (map['title'] as String?) ?? '',
        text: (map['text'] as String?) ?? '',
      );

  /// In-memory id of the native tap target (PendingIntent never leaves the
  /// native side; only this key travels over the channel).
  final String key;
  final String packageName;
  final String appLabel;
  final String title;
  final String text;

  /// What the pill shows as its body line (matches the native overlay).
  String get summary => text.trim().isNotEmpty ? text.trim() : title.trim();
}

/// Live media snapshot streamed over `media_events` (in-memory only).
class MediaPreview {
  const MediaPreview({
    required this.appLabel,
    required this.title,
    required this.artist,
    required this.playing,
    required this.positionMs,
    required this.durationMs,
    required this.art,
  });

  factory MediaPreview.fromMap(Map<String, dynamic> map) => MediaPreview(
        appLabel: (map['appLabel'] as String?) ?? '',
        title: (map['title'] as String?) ?? '',
        artist: (map['artist'] as String?) ?? '',
        playing: map['playing'] == true,
        positionMs: (map['positionMs'] as int?) ?? 0,
        durationMs: (map['durationMs'] as int?) ?? 0,
        art: map['art'] as Uint8List?,
      );

  final String appLabel;
  final String title;
  final String artist;
  final bool playing;
  final int positionMs;
  final int durationMs;

  /// Album art as in-memory PNG bytes (rendered straight from RAM).
  final Uint8List? art;
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
///  - media:    PERSISTENT compact view (round album art + pulsing equalizer
///              bars) while any app plays; tap expands to title/artist +
///              prev/play-pause/next controls that drive the real session
///  - flash:    a notification briefly interrupts whatever is showing
///              (including media), then the pill reverts to the persistent
///              state underneath; starts compact then auto-expands after 500ms
///  - springs:  expand uses an overshooting curve (iOS-island feel)
class IslandPreview extends StatefulWidget {
  const IslandPreview({
    super.key,
    this.previewStream,
    this.mediaStream,
    this.timerStream,
    this.callStream,
  });

  /// Overridable for tests; defaults to the live native alert stream.
  final Stream<Map<String, dynamic>>? previewStream;

  /// Overridable for tests; defaults to the live native media stream.
  final Stream<Map<String, dynamic>?>? mediaStream;

  /// Overridable for tests; defaults to the live native timer stream.
  final Stream<Map<String, dynamic>?>? timerStream;

  /// Overridable for tests; defaults to the live native call stream.
  final Stream<Map<String, dynamic>?>? callStream;

  @override
  State<IslandPreview> createState() => _IslandPreviewState();
}

class _IslandPreviewState extends State<IslandPreview>
    with SingleTickerProviderStateMixin {
  static const double _compactW = 60;
  static const double _compactH = 34;
  static const double _expandedW = 300;
  static const double _minFlashH = 56;
  static const double _maxFlashH = 160;
  static const int _maxBodyLines = 4;
  static const double _mediaCompactW = 112;
  static const double _mediaCompactH = 44;
  static const double _mediaExpandedH = 120;
  static const Duration _animDuration = Duration(milliseconds: 220);
  static const Duration _baseHoldDuration = Duration(milliseconds: 4200);

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
  Timer? _armTimer;
  StreamSubscription<Map<String, dynamic>>? _alertSub;
  StreamSubscription<Map<String, dynamic>?>? _mediaSub;
  AlertPreview? _current;
  MediaPreview? _media;
  bool _mediaExpanded = false;
  Map<String, dynamic>? _timerData;
  Map<String, dynamic>? _callData;
  bool _timerExpanded = false;
  bool _callExpanded = false;
  StreamSubscription<Map<String, dynamic>?>? _timerSub;
  StreamSubscription<Map<String, dynamic>?>? _callSub;

  /// A flash becomes tappable (tap-to-open) only once expanded, mirroring
  /// the native overlay's arming delay.
  bool _flashArmed = false;

  /// Two-stage flash: starts compact (false) then auto-expands to true after 500ms.
  bool _flashExpanded = false;
  Timer? _flashExpandTimer;

  double _fromW = _compactW;
  double _fromH = _compactH;
  double _toW = _compactW;
  double _toH = _compactH;

  @override
  void initState() {
    super.initState();
    _size = AnimationController(vsync: this, duration: _animDuration);
    // Spring-like: slight overshoot on expand (iOS-island feel). The native
    // overlay uses the matching PathInterpolator(0.34, 1.56, 0.64, 1).
    _t = CurvedAnimation(parent: _size, curve: Curves.easeOutBack);
    _alertSub = (widget.previewStream ?? NativeBridge.alertPreviews()).listen(
      _onAlert,
      onError: (Object e, StackTrace st) {
        // Ignore: in tests / before the native side is ready there is simply
        // no alert stream to listen to.
      },
    );
    _mediaSub = (widget.mediaStream ?? NativeBridge.mediaEvents()).listen(
      _onMedia,
      onError: (Object e, StackTrace st) {
        // Ignore: same as above.
      },
    );
    _timerSub = (widget.timerStream ?? NativeBridge.timerEvents()).listen(
      (data) {
        if (!mounted) return;
        setState(() { _timerData = data; if (data == null) _timerExpanded = false; });
        if (_current == null && _media == null) _retarget();
      },
      onError: (Object _, StackTrace _) {},
    );
    _callSub = (widget.callStream ?? NativeBridge.callEvents()).listen(
      (data) {
        if (!mounted) return;
        setState(() { _callData = data; if (data == null) _callExpanded = false; });
        if (_current == null && _media == null) _retarget();
      },
      onError: (Object _, StackTrace _) {},
    );
  }

  double get _nowW => lerpDouble(_fromW, _toW, _t.value) ?? _toW;
  double get _nowH => lerpDouble(_fromH, _toH, _t.value) ?? _toH;

  void _animateTo(double w, double h) {
    _fromW = _nowW;
    _fromH = _nowH;
    _toW = w;
    _toH = h;
    _size.forward(from: 0);
  }

  /// Picks the pill's target geometry for the current state.
  void _retarget() {
    if (_current != null) {
      _animateTo(_flashExpanded ? _expandedW : 80,
                 _flashExpanded ? _flashHeight(_current!) : 44);
    } else if (_callData != null) {
      _animateTo(_callExpanded ? _expandedW : 118, _callExpanded ? 80 : 40);
    } else if (_timerData != null) {
      _animateTo(_timerExpanded ? _expandedW : 118, _timerExpanded ? 110 : 40);
    } else if (_media != null) {
      _animateTo(_mediaExpanded ? _expandedW : _mediaCompactW,
                 _mediaExpanded ? _mediaExpandedH : _mediaCompactH);
    } else {
      _animateTo(_compactW, _compactH);
    }
  }

  void _onAlert(Map<String, dynamic> map) {
    if (!mounted) return;
    final alert = AlertPreview.fromMap(map);
    _flashExpandTimer?.cancel();
    _armTimer?.cancel();
    _hideTimer?.cancel();
    setState(() {
      _current = alert;
      _flashExpanded = false;
      _flashArmed = false;
    });
    _retarget();

    _flashExpandTimer = Timer(const Duration(milliseconds: 500), () {
      if (!mounted || _current != alert) return;
      setState(() => _flashExpanded = true);
      _retarget();
      _armTimer = Timer(_animDuration, () {
        if (mounted) setState(() => _flashArmed = true);
      });
    });

    final hold = const Duration(milliseconds: 500) +
        _animDuration +
        _baseHoldDuration +
        Duration(milliseconds: (alert.summary.length * 8).clamp(0, 4000));
    _hideTimer = Timer(hold, () {
      if (!mounted) return;
      _flashExpandTimer?.cancel();
      setState(() { _current = null; _flashExpanded = false; _flashArmed = false; });
      _retarget();
    });
  }

  /// Tap on an armed, expanded flash: open what the real notification would
  /// open (native fires the stored contentIntent) and collapse.
  void _onFlashTap() {
    final alert = _current;
    if (alert == null || !_flashArmed) return;
    setState(() {
      _current = null;
      _flashArmed = false;
      _flashExpanded = false;
    });
    _retarget();
    NativeBridge.openNotification(alert.key);
  }

  void _onMedia(Map<String, dynamic>? map) {
    if (!mounted) return;
    setState(() {
      _media = map == null ? null : MediaPreview.fromMap(map);
      if (_media == null) _mediaExpanded = false;
    });
    if (_current == null) _retarget();
  }

  void _onTap() {
    if (_current != null) {
      if (!_flashExpanded) {
        _flashExpandTimer?.cancel(); _armTimer?.cancel();
        setState(() { _flashExpanded = true; _flashArmed = false; });
        _retarget();
        _armTimer = Timer(_animDuration, () {
          if (mounted) setState(() => _flashArmed = true);
        });
      } else if (_flashArmed) { _onFlashTap(); }
    } else if (_callData != null) {
      setState(() => _callExpanded = !_callExpanded); _retarget();
    } else if (_timerData != null) {
      setState(() => _timerExpanded = !_timerExpanded); _retarget();
    } else if (_media != null) {
      setState(() => _mediaExpanded = !_mediaExpanded); _retarget();
    }
  }

  /// Mirrors the native overlay's content measurement: height that fits the
  /// label row plus the (multi-line) body, clamped to the pill's bounds.
  double _flashHeight(AlertPreview alert) {
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
    return h.clamp(_minFlashH, _maxFlashH).toDouble();
  }

  /// Slide-in + fade-in animation wrapper for pill content.
  Widget _withSlideIn(Widget child) {
    return AnimatedBuilder(
      animation: _t,
      child: child,
      builder: (_, child2) => Transform.translate(
        offset: Offset(0, (1 - _t.value).clamp(0.0, 1.0) * 8),
        child: Opacity(opacity: _t.value.clamp(0.0, 1.0), child: child2),
      ),
    );
  }

  @override
  void dispose() {
    _flashExpandTimer?.cancel();
    _hideTimer?.cancel();
    _armTimer?.cancel();
    _alertSub?.cancel();
    _mediaSub?.cancel();
    _timerSub?.cancel();
    _callSub?.cancel();
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
          final double w = _nowW;
          final double h = _nowH;
          final bool interactive =
              _current != null || _callData != null || _timerData != null || _media != null;
          return GestureDetector(
            onTap: interactive ? _onTap : null,
            child: ClipRRect(
              borderRadius: BorderRadius.circular(24),
              child: Container(
                width: w,
                height: h,
                color: const Color(0xFF000000),
                child: _current != null
                    ? _withSlideIn(_flashContent(_current!))
                    : _callData != null
                        ? _withSlideIn(_callExpanded
                            ? _callExpandedContent(_callData!)
                            : _callCompactContent(_callData!))
                        : _timerData != null
                            ? _withSlideIn(_timerExpanded
                                ? _timerExpandedContent(_timerData!)
                                : _timerCompactContent(_timerData!))
                            : _media != null
                                ? _withSlideIn(_mediaExpanded
                                    ? _mediaExpandedContent(_media!)
                                    : _mediaCompactContent(_media!))
                                : null,
              ),
            ),
          );
        },
      ),
    );
  }

  // -- content variants ----------------------------------------------------

  Widget _flashContent(AlertPreview alert) {
    if (!_flashExpanded) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 8),
          child: _AppIconBadge(appLabel: alert.appLabel),
        ),
      );
    }
    return Padding(
      padding: const EdgeInsets.only(left: 10, right: 10),
      child: Row(
        children: [
          _AppIconBadge(appLabel: alert.appLabel, size: 36),
          const SizedBox(width: 8),
          Expanded(
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(children: [
                  Expanded(
                    child: Text(alert.appLabel,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: _labelStyle),
                  ),
                  const Text('just now',
                      style: TextStyle(fontSize: 10, color: Color(0xFF6E6E76))),
                ]),
                Text(alert.summary,
                    maxLines: _maxBodyLines,
                    overflow: TextOverflow.ellipsis,
                    style: _bodyStyle),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _art(MediaPreview media, double size) {
    final art = media.art;
    final widget = art != null
        ? Image.memory(art, fit: BoxFit.cover, gaplessPlayback: true)
        : const Icon(Icons.music_note, color: Color(0xFF9E9EA7), size: 18);
    return ClipOval(
      child: Container(
        width: size,
        height: size,
        color: const Color(0xFF17171C),
        alignment: Alignment.center,
        child: widget,
      ),
    );
  }

  Widget _mediaCompactContent(MediaPreview media) {
    return Padding(
      padding: const EdgeInsets.only(left: 8, right: 8),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          _art(media, 28),
          const SizedBox(width: 6),
          _EqBars(active: media.playing, height: 16),
        ],
      ),
    );
  }

  Widget _mediaExpandedContent(MediaPreview media) {
    final double progress = media.durationMs > 0
        ? (media.positionMs / media.durationMs).clamp(0.0, 1.0)
        : 0;
    return Padding(
      padding: const EdgeInsets.only(left: 10, right: 6),
      child: Row(
        children: [
          _art(media, 56),
          const SizedBox(width: 8),
          Expanded(
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(media.title.isEmpty ? media.appLabel : media.title,
                    maxLines: 1, overflow: TextOverflow.ellipsis,
                    style: const TextStyle(color: Colors.white, fontSize: 14,
                                           fontWeight: FontWeight.w500)),
                Text(media.artist.isEmpty ? media.appLabel : media.artist,
                    maxLines: 1, overflow: TextOverflow.ellipsis,
                    style: _labelStyle),
                const SizedBox(height: 6),
                LinearProgressIndicator(
                  value: progress, minHeight: 2,
                  backgroundColor: const Color(0xFF2A2A31),
                  valueColor: const AlwaysStoppedAnimation<Color>(Color(0xFF7ED6DF)),
                ),
              ],
            ),
          ),
          _ControlButton(icon: Icons.skip_previous, onTap: () => NativeBridge.mediaPrev()),
          _ControlButton(
            icon: media.playing ? Icons.pause : Icons.play_arrow,
            onTap: () => NativeBridge.mediaPlayPause(),
          ),
          _ControlButton(icon: Icons.skip_next, onTap: () => NativeBridge.mediaNext()),
        ],
      ),
    );
  }

  String _formatElapsed(int ms) {
    final total = (ms / 1000).round().clamp(0, 359999);
    final h = total ~/ 3600;
    final m = (total % 3600) ~/ 60;
    final s = total % 60;
    if (h > 0) return '$h:${m.toString().padLeft(2,'0')}:${s.toString().padLeft(2,'0')}';
    return '$m:${s.toString().padLeft(2,'0')}';
  }

  Widget _callCompactContent(Map<String, dynamic> data) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 10),
      child: Row(mainAxisAlignment: MainAxisAlignment.center, children: [
        const Icon(Icons.call, color: Color(0xFF30D158), size: 18),
        const SizedBox(width: 6),
        Text(_formatElapsed((data['elapsedMs'] as int?) ?? 0),
            style: const TextStyle(color: Colors.white, fontSize: 13)),
      ]),
    );
  }

  Widget _callExpandedContent(Map<String, dynamic> data) {
    return Row(children: [
      Container(width: 3, color: const Color(0xFF30D158)),
      const SizedBox(width: 10),
      const Icon(Icons.call, color: Color(0xFF30D158), size: 24),
      const SizedBox(width: 8),
      Expanded(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('Call', style: TextStyle(fontSize: 11, color: Color(0xFF9E9EA7))),
            Text(_formatElapsed((data['elapsedMs'] as int?) ?? 0),
                style: const TextStyle(color: Colors.white, fontSize: 20,
                                       fontWeight: FontWeight.w500)),
          ],
        ),
      ),
    ]);
  }

  Widget _timerCompactContent(Map<String, dynamic> data) {
    final kind = (data['kind'] as String?) ?? 'countdown';
    final posMs = (data['positionMs'] as int?) ?? 0;
    final durMs = (data['durationMs'] as int?) ?? 0;
    final display = kind == 'countdown' ? (durMs - posMs).clamp(0, durMs) : posMs;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 10),
      child: Row(mainAxisAlignment: MainAxisAlignment.center, children: [
        Icon(Icons.timer_outlined, size: 18,
            color: kind == 'countdown'
                ? const Color(0xFFFFD60A) : const Color(0xFF7ED6DF)),
        const SizedBox(width: 6),
        Text(_formatElapsed(display),
            style: const TextStyle(color: Colors.white, fontSize: 13)),
      ]),
    );
  }

  Widget _timerExpandedContent(Map<String, dynamic> data) {
    final kind = (data['kind'] as String?) ?? 'countdown';
    final posMs = (data['positionMs'] as int?) ?? 0;
    final durMs = (data['durationMs'] as int?) ?? 0;
    final double arc;
    final int display;
    if (kind == 'countdown') {
      final rem = (durMs - posMs).clamp(0, durMs);
      arc = durMs > 0 ? rem / durMs : 0;
      display = rem;
    } else {
      arc = (posMs % 60000) / 60000;
      display = posMs;
    }
    final arcColor = kind == 'countdown'
        ? const Color(0xFF30D158) : const Color(0xFF7ED6DF);
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 10),
      child: Row(children: [
        CustomPaint(
            size: const Size(64, 64),
            painter: _TimerArcPainter(progress: arc, arcColor: arcColor)),
        const SizedBox(width: 10),
        Column(
          mainAxisAlignment: MainAxisAlignment.center,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(kind == 'countdown' ? 'Timer' : 'Stopwatch',
                style: const TextStyle(fontSize: 11, color: Color(0xFF9E9EA7))),
            Text(_formatElapsed(display),
                style: const TextStyle(color: Colors.white, fontSize: 22,
                                       fontWeight: FontWeight.w500)),
          ],
        ),
      ]),
    );
  }
}

/// Rounded avatar used by the flash mode. (The native overlay shows the
/// real sender icon, which never leaves the native side.)
class _AppIconBadge extends StatelessWidget {
  const _AppIconBadge({required this.appLabel, this.size = 32});

  final String appLabel;
  final double size;

  @override
  Widget build(BuildContext context) {
    const List<Color> palette = [
      Color(0xFF6C5CE7), Color(0xFF00B894), Color(0xFFE17055),
      Color(0xFF0984E3), Color(0xFFE84393), Color(0xFF00CEC9),
    ];
    final tint = palette[appLabel.hashCode.abs() % palette.length];
    return Container(
      width: size, height: size,
      decoration: BoxDecoration(
        color: Color.alphaBlend(tint.withValues(alpha: 0.22), const Color(0xFF17171C)),
        borderRadius: BorderRadius.circular(size * 0.25),
      ),
      alignment: Alignment.center,
      child: Text(
        appLabel.isEmpty ? '?' : appLabel.substring(0, 1).toUpperCase(),
        style: TextStyle(color: Colors.white, fontSize: size * 0.47,
                         fontWeight: FontWeight.w600),
      ),
    );
  }
}

/// Spotify-style pulsing equalizer bars (3 thin bars).
class _EqBars extends StatefulWidget {
  const _EqBars({required this.active, this.height = 16});

  final bool active;
  final double height;

  @override
  State<_EqBars> createState() => _EqBarsState();
}

class _EqBarsState extends State<_EqBars>
    with SingleTickerProviderStateMixin {
  late final AnimationController _c;

  @override
  void initState() {
    super.initState();
    _c = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 620),
    )..repeat();
  }

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: _c,
      builder: (context, _) {
        final t = _c.value * 2 * math.pi;
        return Row(
          crossAxisAlignment: CrossAxisAlignment.end,
          children: List.generate(3, (i) {
            final frac = widget.active
                ? 0.3 + 0.7 * (math.sin(t + i * 2.1).abs())
                : 0.25;
            return Container(
              width: 3,
              margin: EdgeInsets.only(left: i == 0 ? 0 : 3),
              height: (widget.height * frac).clamp(3.0, widget.height),
              color: const Color(0xFF7ED6DF),
            );
          }),
        );
      },
    );
  }
}

/// Tiny transparent transport button for the expanded media pill.
class _ControlButton extends StatelessWidget {
  const _ControlButton({required this.icon, required this.onTap});

  final IconData icon;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      borderRadius: BorderRadius.circular(16),
      onTap: onTap,
      child: SizedBox(
        width: 30,
        height: 30,
        child: Icon(icon, color: Colors.white, size: 20),
      ),
    );
  }
}

class _TimerArcPainter extends CustomPainter {
  final double progress;
  final Color arcColor;
  const _TimerArcPainter({required this.progress, required this.arcColor});

  @override
  void paint(Canvas canvas, Size size) {
    final center = Offset(size.width / 2, size.height / 2);
    final radius = (size.shortestSide / 2) - 4;
    const sw = 4.0;
    final bg = Paint()
      ..style = PaintingStyle.stroke ..strokeWidth = sw
      ..strokeCap = StrokeCap.round ..color = const Color(0xFF2A2A31);
    final fg = Paint()
      ..style = PaintingStyle.stroke ..strokeWidth = sw
      ..strokeCap = StrokeCap.round ..color = arcColor;
    canvas.drawCircle(center, radius, bg);
    if (progress > 0) {
      canvas.drawArc(Rect.fromCircle(center: center, radius: radius),
          -math.pi / 2, progress.clamp(0.0, 1.0) * 2 * math.pi, false, fg);
    }
  }

  @override
  bool shouldRepaint(_TimerArcPainter o) =>
      o.progress != progress || o.arcColor != arcColor;
}
