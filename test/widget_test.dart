import 'dart:async';

import 'package:dynamic_island_app/src/island_preview.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

/// Tests the island pill state machine with an injected fake alert stream
/// (no platform channels involved).
void main() {
  testWidgets('idle pill expands on alert, then collapses and clears',
      (tester) async {
    final controller = StreamController<Map<String, dynamic>>();
    addTearDown(controller.close);

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: Center(
            child: IslandPreview(previewStream: controller.stream),
          ),
        ),
      ),
    );

    // Idle: compact pill, no text.
    expect(find.text('Messages'), findsNothing);

    // An alert arrives -> it expands and shows app + text.
    controller.add(const {
      'packageName': 'com.example.messenger',
      'appLabel': 'Messages',
      'title': 'New message',
      'text': 'Hey, are you free tonight?',
    });
    await tester.pump(); // stream delivery
    await tester.pump(const Duration(milliseconds: 250)); // expansion
    expect(find.text('Messages'), findsOneWidget);
    expect(find.text('Hey, are you free tonight?'), findsOneWidget);

    // After the 3.4 s hold the pill starts collapsing; a little later the
    // collapse finishes and the in-memory content is dropped.
    await tester.pump(const Duration(seconds: 4));
    await tester.pump(const Duration(milliseconds: 300));
    expect(find.text('Messages'), findsNothing);
    expect(find.text('Hey, are you free tonight?'), findsNothing);

    // A second alert while idle works again.
    controller.add(const {
      'packageName': 'com.example.bank',
      'appLabel': 'My Bank',
      'title': 'Spending alert',
      'text': 'You spent \$42.00 at Grocery Store',
    });
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 250));
    expect(find.text('My Bank'), findsOneWidget);
    expect(find.text('You spent \$42.00 at Grocery Store'), findsOneWidget);
  });
}
