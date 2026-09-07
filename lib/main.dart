import 'dart:async';
import 'dart:io' show Platform;

import 'package:flutter/material.dart';

import 'src/island_preview.dart';
import 'src/native_bridge.dart';

void main() {
  runApp(const DynamicIslandApp());
}

class DynamicIslandApp extends StatelessWidget {
  const DynamicIslandApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Isle',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        brightness: Brightness.dark,
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFF0B0B0F),
          brightness: Brightness.dark,
        ),
        scaffoldBackgroundColor: const Color(0xFF101014),
        useMaterial3: true,
      ),
      home: const HomeScreen(),
    );
  }
}

// ---------------------------------------------------------------------------
// Settings screen
// ---------------------------------------------------------------------------

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen>
    with WidgetsBindingObserver {
  IslandStatus _status = const IslandStatus(
    overlayEnabled: false,
    overlayRunning: false,
    canDrawOverlays: false,
    notificationAccessGranted: false,
    ignoringBatteryOptimizations: false,
    postNotificationsPermission: false,
    phoneStatePermission: false,
    timerActive: false,
    callActive: false,
    mediaActive: false,
    unlockFlourish: false,
    dismissOverlayWarning: true,
    islandOnly: true,
  );
  bool _loading = true;
  bool _busy = false;
  StreamSubscription<void>? _statusSub;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _statusSub = NativeBridge.statusChanges().listen((_) => _refresh());
    _refresh();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _statusSub?.cancel();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      // User may have just returned from a system permission screen.
      _refresh();
    }
  }

  Future<void> _refresh() async {
    try {
      final status = await NativeBridge.fetchStatus();
      if (!mounted) return;
      setState(() {
        _status = status;
        _loading = false;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() => _loading = false);
    }
  }

  Future<void> _onToggle(bool enabled) async {
    setState(() => _busy = true);
    try {
      await NativeBridge.setEnabled(enabled);
      if (enabled) {
        // Auto-guide through the permission dance (special settings screens
        // that cannot be triggered by a normal permission dialog).
        final status = await NativeBridge.fetchStatus();
        await _openStep(_nextStep(status));
      }
      await _refresh();
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Isle'),
        centerTitle: false,
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : RefreshIndicator(
              onRefresh: _refresh,
              child: ListView(
                physics: const AlwaysScrollableScrollPhysics(),
                padding: const EdgeInsets.all(16),
                children: [
                  _buildPreviewSection(),
                  const SizedBox(height: 16),
                  _buildMainToggle(),
                  if (_status.overlayEnabled)
                    ..._buildSetupBanner(),
                  const SizedBox(height: 16),
                  _buildPermissionsSection(),
                  const SizedBox(height: 16),
                  _buildLiveModesSection(),
                  const SizedBox(height: 16),
                  _buildSystemStatesSection(),
                  const SizedBox(height: 16),
                  _buildTestSection(),
                  const SizedBox(height: 16),
                  _buildPersistenceSection(),
                  const SizedBox(height: 16),
                  _buildSecuritySection(),
                  const SizedBox(height: 24),
                  _buildAndroidOnlyNote(),
                ],
              ),
            ),
    );
  }

  // -- Preview ------------------------------------------------------------

  Widget _buildPreviewSection() {
    return Column(
      children: [
        const SizedBox(
          height: 72,
          child: Center(child: IslandPreview()),
        ),
        const SizedBox(height: 8),
        Text(
          'Live preview — the real island also floats on top of every other '
          'app, even when this app is closed.',
          textAlign: TextAlign.center,
          style: Theme.of(context).textTheme.bodySmall,
        ),
      ],
    );
  }

  // -- Main switch ---------------------------------------------------------

  Widget _buildMainToggle() {
    final running = _status.overlayRunning;
    return Card(
      margin: EdgeInsets.zero,
      child: SwitchListTile(
        value: _status.overlayEnabled,
        onChanged: _busy ? null : _onToggle,
        title: const Text('Show Isle'),
        subtitle: Text(
          _status.overlayEnabled
              ? (running
                  ? 'Running — floating pill is active.'
                  : 'On, but the service is not running yet '
                      '(check the permissions below).')
              : 'Off. Nothing is captured or displayed.',
        ),
        secondary: Icon(
          _status.overlayEnabled ? Icons.circle : Icons.circle_outlined,
          color: _status.overlayEnabled ? Colors.amber : Colors.grey,
        ),
      ),
    );
  }

  // -- Guided setup banner ---------------------------------------------------

  List<Widget> _buildSetupBanner() {
    final step = _nextStep(_status);
    if (step == null) return const [];
    return [
      const SizedBox(height: 12),
      Card(
        margin: EdgeInsets.zero,
        color: Theme.of(context).colorScheme.secondaryContainer,
        child: ListTile(
          leading: Icon(step.icon),
          title: const Text('Next setup step'),
          subtitle: Text(step.instruction),
          trailing: FilledButton(
            onPressed: _busy ? null : () => _openStep(step),
            child: const Text('Open'),
          ),
        ),
      ),
    ];
  }

  // -- Permissions -----------------------------------------------------------

  Widget _buildPermissionsSection() {
    final s = _status;
    return Card(
      margin: EdgeInsets.zero,
      child: Column(
        children: [
          const ListTile(
            leading: Icon(Icons.perm_device_information),
            title: Text('Permissions & special access'),
            subtitle: Text('These are Android system screens — '
                'each must be granted once, manually.'),
          ),
          const Divider(height: 1),
          _PermissionTile(
            icon: Icons.layers,
            title: 'Display over other apps',
            subtitle: 'Draws the floating pill above every app. Android '
                'requires opening the special "Display over other apps" '
                'settings page — there is no normal permission dialog.',
            why: 'Android does not offer a normal allow/deny dialog for '
                'drawing over other apps — it can only be granted on the '
                'special "Display over other apps" settings page, which is '
                'why the button below deep-links there '
                '(Settings.ACTION_MANAGE_OVERLAY_PERMISSION). Without it the '
                'system rejects Isle\'s overlay window and the pill cannot '
                'appear above other apps. The pill never blocks touches: '
                'taps outside it pass straight through.',
            whyActionLabel: 'Open the settings page',
            granted: s.canDrawOverlays,
            grantedLabel: 'Granted',
            actionLabel: 'Open settings',
            onAction: () => NativeBridge.openOverlayPermissionSettings(),
          ),
          SwitchListTile(
            secondary: const Icon(Icons.notifications_off_outlined, size: 20),
            title: const Text('Hide "displaying over other apps" notice'),
            subtitle: const Text(
              'Android re-posts a system warning whenever an overlay app '
              'shows (e.g. after each unlock). The OS cannot be stopped from '
              'posting it, but Isle can dismiss that one notice instantly. '
              'Nothing else is ever dismissed.',
            ),
            value: s.dismissOverlayWarning,
            onChanged: (v) => NativeBridge.setDismissOverlayWarning(v),
          ),
          _PermissionTile(
            icon: Icons.notifications_active_outlined,
            title: 'Notification access',
            subtitle: 'Lets the listener read incoming notification titles '
                'and text (kept in memory only, never stored). Must be '
                'toggled on manually inside the system screen.',
            why: 'Reading other apps\' notifications is so sensitive that '
                'Android provides no runtime dialog for it at all — you must '
                'find Isle in the system "Notification access" screen '
                '(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) and flip '
                'the toggle yourself. Isle uses it only to mirror '
                'notifications into the island: content lives in RAM for the '
                'seconds it is visible and is never written to disk or sent '
                'anywhere (the app has no INTERNET permission in any build '
                'variant).',
            whyActionLabel: 'Open the settings page',
            granted: s.notificationAccessGranted,
            grantedLabel: 'Listening',
            actionLabel: 'Enable access',
            onAction: () => NativeBridge.openNotificationAccessSettings(),
          ),
          SwitchListTile(
            secondary: const Icon(Icons.do_not_disturb_on_outlined, size: 20),
            title: const Text('Island-only notifications'),
            subtitle: const Text(
              'Notifications show ONLY in the island — the system copy is '
              'dismissed instantly, so the shade never collects them. '
              'Trade-off: no shade history or quick reply once the flash '
              'ends. Ongoing notifications (media players, calls) always '
              'stay in the shade so their controls keep working. Turn off '
              'to mirror normally.',
            ),
            value: s.islandOnly,
            onChanged: (v) => NativeBridge.setIslandOnly(v),
          ),
          if (!s.postNotificationsPermission)
            _PermissionTile(
              icon: Icons.notifications_none,
              title: 'Notifications (Android 13+)',
              subtitle: 'Lets the small "Notification overlay running" item '
                  'appear. The overlay still works if you keep this off.',
              granted: false,
              grantedLabel: '',
              actionLabel: 'Allow notifications',
              onAction: () => NativeBridge.requestPostNotificationsPermission(),
            ),
          _PermissionTile(
            icon: Icons.battery_saver,
            title: 'Battery optimization exemption',
            subtitle: 'Prevents Android from killing the overlay service, so '
                'it survives closing the app and restarts after a reboot.',
            why: 'Without the exemption, aggressive battery management can '
                'kill the background service minutes after you close the '
                'app, and the island would stop until you reopen Isle. '
                'Requesting it shows Android\'s one-time "Don\'t optimize" '
                'dialog (ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS); if '
                'Isle is already exempt nothing appears and the full list '
                'screen opens instead.',
            whyActionLabel: 'Request exemption',
            granted: s.ignoringBatteryOptimizations,
            grantedLabel: 'Exempt',
            actionLabel: 'Request exemption',
            onAction: () => NativeBridge.openBatteryOptimizationSettings(),
          ),
        ],
      ),
    );
  }

  // -- Live modes (media / call / timer) -----------------------------------

  Widget _buildLiveModesSection() {
    return Card(
      margin: EdgeInsets.zero,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const ListTile(
            leading: Icon(Icons.bubble_chart_outlined),
            title: Text('Live island modes'),
            subtitle: Text(
              'Priority when several are active at once: call > timer > '
              'media > notification flash.',
            ),
          ),
          SwitchListTile(
            secondary: const Icon(Icons.lock_open, size: 20),
            title: const Text('Unlock flourish'),
            subtitle: const Text(
              'Off by default: after unlocking, the island shows nothing '
              'over your apps. Enable to get a brief open-lock glyph in the '
              'pill right after each unlock.',
            ),
            value: _status.unlockFlourish,
            onChanged: (v) => NativeBridge.setUnlockFlourish(v),
          ),
          _Bullet(
            icon: Icons.music_note_outlined,
            text: 'Media: while any app (Spotify, YouTube Music, ...) is '
                'actively playing, the island shows album art + a waveform. '
                'Tap it for play/pause/skip controls. Paused/stopped apps '
                'never pin a pill.',
          ),
          _Bullet(
            icon: Icons.call_outlined,
            text: _status.phoneStatePermission
                ? 'Call: a live duration timer runs in the island during '
                    'phone calls.'
                : 'Call: allow the (read-only) phone-state permission to '
                    'show a live call timer.',
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 4, 16, 12),
            child: Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                if (!_status.phoneStatePermission)
                  TextButton.icon(
                    onPressed: _status.overlayEnabled
                        ? () => NativeBridge.requestPhoneStatePermission()
                        : null,
                    icon: const Icon(Icons.call, size: 18),
                    label: const Text('Enable call timer'),
                  ),
                for (final mins in const [1, 5, 10])
                  OutlinedButton(
                    onPressed: _status.overlayEnabled
                        ? () => NativeBridge.startCountdown(mins * 60)
                        : null,
                    child: Text('$mins min'),
                  ),
                OutlinedButton.icon(
                  onPressed: _status.overlayEnabled
                      ? () => NativeBridge.startStopwatch()
                      : null,
                  icon: const Icon(Icons.timer_outlined, size: 18),
                  label: const Text('Stopwatch'),
                ),
                if (_status.timerActive)
                  TextButton.icon(
                    onPressed: () => NativeBridge.stopTimer(),
                    icon: const Icon(Icons.stop, size: 18),
                    label: const Text('Stop'),
                  ),
              ],
            ),
          ),
          const _Bullet(
            icon: Icons.lock_outline,
            text: 'Android never lets overlay windows cover a secure lock '
                'screen (PIN/pattern/biometric) — the island appears again '
                'the moment the phone is unlocked.',
          ),
          const SizedBox(height: 8),
        ],
      ),
    );
  }

  // -- System hardware states ---------------------------------------------

  Widget _buildSystemStatesSection() {
    return Card(
      margin: EdgeInsets.zero,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const ListTile(
            leading: Icon(Icons.hardware_outlined),
            title: Text('System hardware states'),
            subtitle: Text(
              'Brief island flashes for charging, low battery, ringer mode, '
              'and Focus/DND changes. Always active when the overlay is running.',
            ),
          ),
          _PermissionTile(
            icon: Icons.privacy_tip_outlined,
            title: 'Privacy indicators',
            subtitle: 'Shows an orange dot (microphone) or green dot (camera) '
                'alongside the island when any app accesses them. Requires '
                '"Usage access" — a special system permission.',
            why: 'Android\'s AppOpsManager.startWatchingActive() can monitor '
                'camera and microphone usage across all apps, but requires the '
                '"Usage access" special permission (PACKAGE_USAGE_STATS). '
                'Without it the privacy dots simply stay hidden — everything '
                'else keeps working normally.',
            whyActionLabel: 'Open Usage access settings',
            granted: _status.usageStatsGranted,
            grantedLabel: 'Active',
            actionLabel: 'Enable',
            onAction: () async {
              await NativeBridge.openUsageAccessSettings();
            },
          ),
          const _Bullet(
            icon: Icons.battery_charging_full_outlined,
            text: 'Charging connected or unplugged — brief green/white flash.',
          ),
          const _Bullet(
            icon: Icons.battery_alert_outlined,
            text: 'Low battery at 20% (yellow) and 10% (red).',
          ),
          const _Bullet(
            icon: Icons.volume_off_outlined,
            text: 'Silent / Vibrate / Ring toggle — 2-second flash.',
          ),
          const _Bullet(
            icon: Icons.do_not_disturb_on_outlined,
            text: 'Focus Mode / Do Not Disturb on or off — 2-second flash.',
          ),
          const SizedBox(height: 8),
        ],
      ),
    );
  }

  // -- Test ---------------------------------------------------------------

  Widget _buildTestSection() {
    return Card(
      margin: EdgeInsets.zero,
      child: ListTile(
        leading: const Icon(Icons.science_outlined),
        title: const Text('Try it now'),
        subtitle: Text(_status.overlayEnabled
            ? 'Fire a synthetic alert to see the island expand. '
                '(Nothing real is posted to your notifications.)'
            : 'Turn the switch on first.'),
        trailing: OutlinedButton.icon(
          onPressed: _status.overlayEnabled
              ? () => NativeBridge.fireTestAlert()
              : null,
          icon: const Icon(Icons.play_arrow, size: 18),
          label: const Text('Show test alert'),
        ),
      ),
    );
  }

  // -- Persistence / background ------------------------------------------------

  Widget _buildPersistenceSection() {
    return Card(
      margin: EdgeInsets.zero,
      child: const Column(
        children: [
          ListTile(
            leading: Icon(Icons.bolt_outlined),
            title: Text('Works when the app is closed'),
          ),
          _Bullet(
            icon: Icons.hourglass_top,
            text: 'A foreground service owns the overlay. Swiping the app '
                'away from Recents does not stop it.',
          ),
          _Bullet(
            icon: Icons.restart_alt,
            text: 'A BOOT_COMPLETED receiver restarts everything '
                'automatically after a phone reboot.',
          ),
          _Bullet(
            icon: Icons.visibility_off_outlined,
            text: 'The only visible trace is a generic '
                '"Notification overlay running" item — it never contains '
                'notification content.',
          ),
          _Bullet(
            icon: Icons.toggle_off_outlined,
            text: 'Turning the switch off (or revoking overlay permission) '
                'stops the service.',
          ),
        ],
      ),
    );
  }

  // -- Security reminder ------------------------------------------------------

  Widget _buildSecuritySection() {
    return Card(
      margin: EdgeInsets.zero,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(12),
        side: BorderSide(
          color: Theme.of(context).colorScheme.error.withValues(alpha: 0.6),
        ),
      ),
      child: const Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: EdgeInsets.fromLTRB(16, 16, 16, 4),
            child: Row(
              children: [
                Icon(Icons.security, color: Colors.orangeAccent),
                SizedBox(width: 8),
                Text('Privacy & security — read me',
                    style: TextStyle(fontWeight: FontWeight.bold)),
              ],
            ),
          ),
          Padding(
            padding: EdgeInsets.fromLTRB(16, 4, 16, 8),
            child: Text(
              'This app reads the content of ALL notifications, including '
              'OTPs, banking alerts, and private messages. It is built for '
              'one person: you, on your own device. It is intentionally NOT '
              'designed for sharing or publishing — never install it on '
              'someone else\u2019s phone without re-evaluating every decision '
              'below.',
              style: TextStyle(fontSize: 13, height: 1.4),
            ),
          ),
          _Bullet(
            icon: Icons.memory,
            text: 'Captured content lives only in memory for the seconds it '
                'is on screen, then is discarded. Nothing is written to '
                'disk or databases.',
          ),
          _Bullet(
            icon: Icons.cloud_off,
            text: 'No network, period: the app declares no INTERNET '
                'permission in its manifest, so it cannot transmit '
                'anything, ever.',
          ),
          _Bullet(
            icon: Icons.terminal,
            text: 'No logging of notification content, no analytics, no '
                'crash reporting, no telemetry SDKs.',
          ),
          _Bullet(
            icon: Icons.code,
            text: 'The overlay + notification listener are hand-written '
                'native Kotlin — no third-party overlay or listener packages.',
          ),
          _Bullet(
            icon: Icons.lock_outline,
            text: 'The persistent service notification shows only a generic '
                '"Notification overlay running" label.',
          ),
        ],
      ),
    );
  }

  Widget _buildAndroidOnlyNote() {
    final text = Platform.isAndroid
        ? 'Android-only build. iOS does not allow apps to draw overlays or '
            'read other apps\u2019 notifications, so this feature cannot '
            'exist there.'
        : 'This app must run on Android. iOS does not allow overlays or '
            'reading other apps\u2019 notifications, so it is disabled on '
            'this platform.';
    return Center(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 24),
        child: Text(
          text,
          textAlign: TextAlign.center,
          style: Theme.of(context).textTheme.bodySmall?.copyWith(
                color: Theme.of(context).colorScheme.outline,
              ),
        ),
      ),
    );
  }

  // ---------------------------------------------------------------------------
  // Guided step helpers
  // ---------------------------------------------------------------------------

  _SetupStep? _nextStep(IslandStatus s) {
    if (!s.overlayEnabled) return null;
    if (!s.canDrawOverlays) return _SetupStep.overlayPermission;
    if (!s.notificationAccessGranted) return _SetupStep.notificationAccess;
    if (!s.postNotificationsPermission) return _SetupStep.postNotifications;
    if (!s.ignoringBatteryOptimizations) return _SetupStep.batteryOptimization;
    return null;
  }

  Future<void> _openStep(_SetupStep? step) async {
    switch (step) {
      case _SetupStep.overlayPermission:
        await NativeBridge.openOverlayPermissionSettings();
        break;
      case _SetupStep.notificationAccess:
        await NativeBridge.openNotificationAccessSettings();
        break;
      case _SetupStep.postNotifications:
        await NativeBridge.requestPostNotificationsPermission();
        await _refresh();
        break;
      case _SetupStep.batteryOptimization:
        await NativeBridge.openBatteryOptimizationSettings();
        break;
      case null:
        break;
    }
  }
}

enum _SetupStep {
  overlayPermission(
    icon: Icons.layers,
    instruction: 'Grant "Display over other apps" so the island can float '
        'above every app.',
  ),
  notificationAccess(
    icon: Icons.notifications_active_outlined,
    instruction: 'Turn on notification access for Isle so it can '
        'see incoming alerts.',
  ),
  postNotifications(
    icon: Icons.notifications_none,
    instruction: 'Allow notifications so the tiny "overlay running" item '
        'can show.',
  ),
  batteryOptimization(
    icon: Icons.battery_saver,
    instruction: 'Request a battery optimization exemption so the service '
        'survives reboot & app close.',
  );

  const _SetupStep({required this.icon, required this.instruction});

  final IconData icon;
  final String instruction;
}

// ---------------------------------------------------------------------------
// Small shared widgets
// ---------------------------------------------------------------------------

class _PermissionTile extends StatelessWidget {
  const _PermissionTile({
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.granted,
    required this.grantedLabel,
    required this.actionLabel,
    required this.onAction,
    this.why,
    this.whyActionLabel,
  });

  final IconData icon;
  final String title;
  final String subtitle;
  final bool granted;
  final String grantedLabel;
  final String actionLabel;
  final VoidCallback onAction;

  /// Longer "why is this needed" explanation, shown in a dedicated dialog
  /// (Android's special-access grants have no normal runtime dialog, so the
  /// app must explain them itself before deep-linking to system settings).
  final String? why;
  final String? whyActionLabel;

  void _showWhy(BuildContext context) {
    showDialog<void>(
      context: context,
      builder: (context) => AlertDialog(
        icon: Icon(icon, size: 36),
        title: Text(title),
        content: Text(why ?? subtitle),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: const Text('Close'),
          ),
          FilledButton(
            onPressed: () {
              Navigator.of(context).pop();
              onAction();
            },
            child: Text(whyActionLabel ?? actionLabel),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return ListTile(
      leading: Icon(icon),
      title: Text(title),
      subtitle: Text(subtitle),
      trailing: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (why != null)
            IconButton(
              tooltip: 'Why is this needed?',
              icon: const Icon(Icons.info_outline),
              onPressed: () => _showWhy(context),
            ),
          granted
              ? Chip(
                  avatar: Icon(Icons.check_circle,
                      size: 18, color: Colors.greenAccent),
                  label: Text(grantedLabel),
                  visualDensity: VisualDensity.compact,
                )
              : TextButton(onPressed: onAction, child: Text(actionLabel)),
        ],
      ),
    );
  }
}

class _Bullet extends StatelessWidget {
  const _Bullet({required this.icon, required this.text});

  final IconData icon;
  final String text;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 4, 16, 4),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.only(top: 2),
            child: Icon(icon, size: 18, color: Colors.amber.shade200),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Text(text, style: const TextStyle(fontSize: 13, height: 1.4)),
          ),
        ],
      ),
    );
  }
}
