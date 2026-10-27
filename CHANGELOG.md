# Changelog

All notable changes to the app. Versions follow [semantic versioning](https://semver.org/).

## Unreleased

### Home screen
- Xiaomi TVs on Android TV (the Mi TV 4i and others), Redmi sets among them, can now get the
  new home screen; their setup wizard stays on. This is for the sets whose only home screen is
  Google's Android TV home and whose setup wizard does not answer the Home button.
- Each time you open Manage with the TV connected, the app checks the Home button again. If the
  setup wizard could now take it while the new home screen is in charge, Manage says so and offers
  to switch the TV's own home screen back on, so the TV keeps starting normally.
- Switching the TV's own home screen back on from "Switched off" (or "Better back on") keeps the
  new one as Home. If the TV still gives Home to the app you switched on, the app says so and
  offers to switch it off again.
- Right after the switch, if the TV brings up Live TV or another input in front of the new home
  screen (some TVs start on the last input they showed), the check presses Home again for you.
- "Use the TV's own home screen" in Manage now also works on TVs with no list of their own.
- A Xiaomi TV with a home screen app you installed next to Google's Android TV home can get the
  new home screen too. Your app stays installed; if it takes the Home button during the switch, the
  app puts the TV's own home screen back.
- When another home screen app is the reason the app left the home screen alone, Setup and Home
  screen now say so, instead of naming the setup wizard.
- If Setup left the home screen alone with an earlier version of the app (for example before your
  TV had a list of its own), Manage's Home screen now says so and offers to run Setup again, and the
  report says the decision is an earlier Setup's.

### Reporting a problem
- Every stop, timeout, paused change and connect problem has "Report a problem" on
  its card, and so does Home screen when the new home screen can't go on the TV. One tap opens a
  ready filled GitHub issue with what the card said and how the TV is now, so there is nothing to
  paste. The app still sends nothing itself: your browser opens the form, and nothing is public
  until you post it.
- A long report always fits in the form now. It keeps the TV, its list and its home screen lines,
  what went wrong and the newest lines of the log, and the app copies the whole report too. Before,
  a report too long for the link left the box empty and asked you to paste it.
- When the phone can't connect, the report has the phone's side instead of the TV's: how the TV was
  found, the port, the error the app named and the phone's Android version. The app hasn't read
  the TV yet, so type its brand and model into the form's TV box.
- Links to GitHub are shorter, so they open for people signed in to GitHub too (a long link and the
  browser's sign-in data together got an error page). The "Help add this TV" report doesn't fit in
  such a link, so the app copies it and you paste it into the form, as before for long reports.
- Lines that repeat in the log, like a connection being tried again every few seconds, are counted
  once ("x12") instead of filling the report.
- The issue's title names the TV, for example "Problem: Xiaomi MiTV-AXSO0 (dangal, Android 9)", or
  "Problem: could not connect (refused)" when no TV was read.

### Under the hood
- Test builds can offer the switch with the setup wizard left on to other brands' sets that look the
  same, so volunteers can check them across restarts. Releases do this on Xiaomi sets only.

## 0.4.5 (2026-10-27)

The home screen app from Gathin's Clean Your TV goes to 3.0, with more choices in the Look sheet, 25
more TV lists and a "Better back on" offer for apps an earlier list switched off.

### Home screen
- Home screen 3.0 is in the app. A TV that has 2.7 sees "Update the home screen" in Manage, and
  nothing changes until you tap it. The update keeps your look, your dock and your remote buttons.
- New in the Look sheet on home screen 3.0:
  - Every app in one row: your dock apps first, then all the others, in a row that slides sideways.
  - A dock with no band, so the apps sit right on your picture.
  - The greeting above the clock, on or off.
  - The date under the clock, and a brighter date.
  - A warning on the Wi-Fi icon: orange when the Wi-Fi works but the internet doesn't, red when the
    TV is offline.
  - App tiles: each app's own art, or its icon on a card in its own colour.
  - The highlight on the app you are on: glass, a thin rim or a halo.
  Rows only appear when the TV's home screen is 3.0 or newer, and the preview shows them.
- Looks you already sent stay exactly as they are, byte for byte, until you send a new one.

### TV lists
- Lists from Clean Your TV up to 26 October: 173 TV lists in the app, 25 more than 0.4.0. New:
  Aqua Matrix TV EE 50, Epson LS800, Hisense Fire TV (AFTCIBFBD1), JVC LT-55MB508, Lumio Vision 7,
  NVIDIA SHIELD TV (2017), OnePlus TV (Cebu and Dosa), Philips Google TV TA1 and TA7, Redmi Mi TV
  MFTP0, Sber SaluteTV AT30A6, Sharp 2T-C32GH3000I, Sony BRAVIA VU1 and VU3, TCL 50DP660, TCL G08
  (EU, Android 12), Motorola MotorolaTV (MT4310), Thomson 43RT1055, Xiaomi Mi Box S (Android 12) and Xiaomi Mi TV ASTP0, AYFR0,
  MSSP0, MSSP2 and MTEQ0.
- Lists we already had for TCL, Xiaomi, Sony, Philips, Amazon and others got their contributors'
  fixes. The Sony BRAVIA BF1 list now matches BF1 sets, the Skyworth SWTV-22AE list works on
  Android 11, and the Mi Box S list covers older firmware.
- The Sony BRAVIA VU3 keeps its own home screen, so the remote's app buttons (Netflix, YouTube,
  Prime Video and YouTube Music) keep working. The app still switches off the adverts and tracking.
- A TV that shows Google TV's home screen no longer gets a list written for the Android TV version
  of the same model; it is set up like any TV no list knows, with its brand set. This fixes
  the TCL Smart TV Pro (BeyondTV4) on its Google TV build AR11, which got the TCL 65C725 Android TV
  list and lost the remote's virtual keys helper (#114, #390).
- PatchWall on Xiaomi TVs is now your choice, kept on unless you switch it off in Customise: with
  it off, the remote's PatchWall button stops working while the TV's own home screen is in use.
  This covers the Xiaomi brand set, every Xiaomi list and the Mi TV 4i test list (#111).
- The Motorola MotorolaTV matches a tested list now (#446). When a TV's start-up
  fallback screen (such as the TCL Percee TV's FallbackHomeCustomizer) also answers Home, the card
  no longer names it as another home screen app (#481).

### Better back on
Some TVs need an app that an earlier list switched off. If this app switched one of them off on
your TV, Manage now says so under "Better back on" and offers to switch it back on, with the reason:
- the TCL G10's remote mic service;
- the update helper on the TCL G03;
- the updater on the TCL ak30a5;
- the stock home screen and setup wizard on the Kalley R10G (the wizard is offered once the TV's
  own home screen is back in charge).

### Safety
- After the home screen update, it restarts cleanly and its remote helpers come back.
- "Undo everything" is no longer refused for apps that are protected, and it never switches
  accessibility on.
- The restore point never records a chooser or a placeholder as your home screen.
- A TV whose home screen is undecided keeps every home screen app protected.

### Smaller fixes
- The Look preview's description for screen readers names the new options.
- The restore point's file list is right after an undo.
- Undo tidies the watchdog files it leaves behind.
- Clearer wording while the new home screen is removed.
- With "Keep debugging on", the Done screen says debugging stays on.
- The licences screen now says MIT for Clean Your TV.

### Connecting
- Phones on Android 9 to 12 can connect to a TV again: the step that hands the TV the phone's key
  used a part of Java those phones do not have, so the TV never showed Allow and the app said
  "unreachable" (#98, #325, #468, #783, #837, #965, #991, #1006, #1024, #1089, #1134). Saving a
  restore file had the same kind of problem on phones below Android 15, fixed too.
- A TV that drops off the Wi-Fi in the middle of a step no longer leaves the setup hanging on the
  Working screen: the step now times out and the run pauses, so you can carry on or undo. After a
  step times out the app also checks once whether the TV still answers; if not, it shows the TV as
  away and opens a fresh connection when it is back, instead of carrying on over the dead one (#616).
- When the TV's port answers and the connection then fails inside the app, the log and Copy error
  now say why, for example that the phone's key could not sign the TV's request, instead of
  "unreachable" with no reason, and the connection is closed (#325, #620).
- The computer steps for a TV that needs a pairing code end with
  `adb -s ADDRESS:CONNECT_PORT tcpip 5555`: after `adb pair` and `adb connect` the computer lists
  the TV twice, and a plain `adb tcpip 5555` stopped with "more than one device". They also say to
  run the last two commands again after the TV restarts, with no new pairing (#655, #643, #768).

### Setting up
- A run that stopped before its first step (the TV went away while the restore point was saved,
  or the app was closed) saves its restore point when you carry on, and checks every step against
  the TV again, as a new run does (#616).
- When the home screen check puts the old home screen back because a Settings page was open on the
  TV (the new home screen's network or Settings button, or the remote's Settings button), the card
  now says so: nothing is wrong with the TV, try again and press only Home. The check screen also
  asks you to press only Home and not open anything else (#629).
- When reading the TV fails, the log line now says what the app found, for example "getprop has
  no ro.product.model", instead of only the step that failed (#788, #1112).
- When the home screen check timed out and the TV put its old home screen back, Try again now
  reads the TV and runs the switch again, and OK keeps the old home screen. Before, the two buttons
  were swapped, so Try again ended on "Your TV is ready" with nothing switched. The last screen now
  says when the home screen stayed and why, and counts only what is really switched off.
- Stop here, and a home screen check that puts the old home screen back, now remove the app's
  full-screen page from the TV instead of leaving it running.
- When the TV changed since the app last read it, a paused change says so ("changed since we last
  read it", nothing was changed) and Check again reads the TV and plans from that, instead of
  pausing again.

## 0.4.0 (2026-10-26)

A new Look sheet for the TV's home screen: pick a picture, pick one of five styles, pick how many
apps sit in a row, and see your own TV in a preview before anything reaches it.

### Home screen
- The Look sheet starts with the picture, then the style, then apps in a row. Nothing is sent to
  the TV until you tap "Put it on the TV".
- Five styles, each shown as a small preview of your TV with your picture: Classic (a dark dock, a
  big name and a soft glow), Glass (a frosted dock and light in each app's colour), Spotlight (a
  big banner of the app you are on, like newer smart TVs, with the app's colour across the top),
  All apps (every app in one grid, no dock band) and Calm (no glow and no big name, just apps and
  your picture).
- Pictures to go with any style: Mist, Aurora, Navy, Autumn lake, Curtains, Mountains (with a sky
  that changes with the hour), Lamp light, Neon, Cobalt, no picture, or your own photo. Diwali is
  there until 10 November, with its lamps and festive icons. The pictures are our own work, free
  to use (CC0).
- Apps in a row: 4, 5, 6, 7 or 8. Fewer apps means bigger tiles, and 4 is the biggest the TV can
  show. At 8 the tiles are small and long names get cut short (the TV's own settings stop at 7).
  "Names under every app" puts each app's name under its tile, easier to read from the sofa.
- More options, for when you want to tweak a style: how the highlight moves (Lively, Smooth, Quick
  or Off), the big name, the glow (soft, from the top or none), a ring in the app's colour, the
  dock band (solid, see-through or glass), the buttons at the top (with names, round icons or one
  menu button) and a green Wi-Fi icon when the TV is online. Rows the style can't use say why.
  "Use Classic's own settings" (or whichever style you're on) puts them all back.
- TVs with 1.5 GB of memory or less get Quick movement picked for them, so Home stays snappy. A
  style change that drops one of your tweaks says so, with Keep mine.
- Advanced: lift and zoom on the app you're on, how see-through the dock band is, how strong the
  sky is, the home screen's own setting names, and the settings file itself with a Copy button.
- Changed your mind? "Undo changes" takes the sheet back to how it opened, and "Put back the earlier
  look" brings back the look the TV had before your last send, photo and all. Closing with changes
  asks first.
- The preview is drawn from the same settings the TV gets, laid out the way the home screen app
  2.7 lays out Home: the picture, its dim, the sky, the dock band, your apps, the focus glow and
  the buttons at the top.
- Your own photo (Manage, then Home screen, then Look): "Choose a photo" opens Android's photo
  picker (no permission needed), then you pinch and drag it in a 16:9 frame with the clock and the
  dock drawn over it, choose how dark it is and tap "Use this crop". The TV gets a 1920 x 1080 copy
  under 280 KB without the photo's place, camera or time, and the phone keeps that copy for the TV.
- Your dock keeps its order when you go to fewer apps in a row. Say you had 15 apps 7 in a row and
  went to 4: the TV shows the first 12, the other 3 wait (the Look and Dock sheets say so), and
  going back brings all 15 back in the same order. Empty spaces in the dock stay empty on the TV
  now, instead of filling up with whatever app comes next.
- Home's Look row shows a small preview of what you last sent, like "Glass with Mist" and "5 apps
  in a row". If a look doesn't reach the TV, the phone keeps your choice and Home offers Open Look.
- Setup's Look step is the same sheet with Done at the bottom, and running Setup again starts from
  the look you saved.
- A look you picked before this version stays on the TV exactly as it is, byte for byte, until you
  send a new one. It opens in the new sheet as the closest style with its own picture and apps in
  a row.
- On a TV with a home screen app older than 2.7, Classic is the one style it can show, and the
  sheet offers the update.
- After Diwali ends, the phone counts your earlier picture as the TV's again and Home offers to
  put it back with one tap.

### Credits
- The look pictures are this project's own work, drawn from code and released under CC0. Aurora,
  Navy (the launcher's Glass picture) and Autumn lake (the launcher's Ruska, named for what it
  shows) come from Clean Your TV (MIT). The app's Licences screen lists them.

### Other
- The app now refuses a home screen settings file with a number that isn't finite in a list, which
  makes the home screen app 2.7 stop on every start. The app never writes one itself.
- The README links to Obtainium, which follows the GitHub releases and updates the app by itself.
- Picking TCL under "Change model" now asks whether the TV's menus are in English (or your
  language) or in Chinese. TCL TVs sold outside China get the usual Developer options steps,
  with a line for Google TV's Wireless debugging; only Chinese menus get the 本机信息 steps.

## 0.3.7 (2026-10-25)

Seventeen more TVs with a tested list, the home screen app updated to 2.7, and more apps this app
never switches off. Thank you to Gathin for the lists, and to everyone who tested one on their TV
and sent a report: each of these lists starts from one.

### TV lists
- 17 more TVs match a tested list, 148 in all, from the lists of Clean Your TV v2.7.0: the
  Amazon Fire TV AFTDEC012E (issues #65, #76), the Chromecast with Google TV HD (#239), the
  Hisense SmartTV 4K FFM on songshan, also the Hisense 50A6H4 (#63, #68, #69, #107, #110, #146,
  #289), and on lushan (#52), the Karbonn SWTV-20AE (#81), the OK. AI PONT (#165, #179, #256),
  the Philips 50PUD7406 and 55PUS7906 (#13, #39, #75, #266), the Skyworth SW-22SA (#89) and
  SWTV-22AE-FHD (#185, #215, #216), the Sony BRAVIA 4K UR3 (#42, #73, #90, #113, #228, #246,
  #255, #269), BRAVIA 4K VH1 (#142, #143, #155, #178, #233, #268, #274) and KD-43X80L (#46,
  #124, #293), and the Xiaomi Mi Box 3 (#199, #224, #273), MiTV-MFTR0 (#193, #249), TV S Mini
  LED 75 2025 (#128), Mi TV P1 43 (#184) and MiTV-MSSP1 (#57, #58, #78, #176).
- The Fire TV AFTDEC012E, the MiTV-MFTR0 and the Chromecast with Google TV HD get the clean-up
  and keep their own home screen: Fire OS keeps its Home, the app does not know the MFTR0's China
  home screen, and on the Chromecast the switch would cost casting after every reboot.
- Ten lists changed upstream and follow it, among them the Hisense SmartTV 4K FFM, the Sony
  BRAVIA 4K UR2 and VH2, the TCL G05 and G09 Google TV and the Xiaomi TV A Pro 2026. The Xiaomi
  MiTV-AESP0 list keeps its setup wizard on, as in 0.3.6; the wizard off option upstream added
  for it costs casting, so its rows are a choice instead.
- Hisense TVs without an exact list also switch off Google partner setup and Calendar sync.
- The other brand sets are unchanged.

### Home screen launcher
- Bundles the Clean Your TV launcher 2.7 (upstream v2.7.0), built from source and signed with
  this project's launcher key. With its default settings the TV looks as before, with fixes: the
  network pill shows the VPN badge right away, the dock's corners follow its tiles, Launcher
  settings changes apply when you leave with Home, and the quick settings no longer crash on
  Android 8.0. It also speaks Ukrainian. Its new options are all off by default; the app lets
  you choose them in a later version.
- A TV set up with an earlier version is offered "Update the home screen" in Manage.

### Safety
- Never switched off, by any list, also during the home screen switch: vendor setup wizards
  (OnePlus, Xiaomi and Android's device provisioning), vendor home screens (Haier, OnePlus),
  TalkBack and Google's text-to-speech, the Sony accessibility tutorial and the TCL hearing aid,
  the remote's services (the Google TV remote service and its logger, Sony remote start and the
  OnePlus remote diagnosis), the Xiaomi TV service and TCL Live. Some upstream lists switch these
  off; this app keeps them on.
- When a vendor setup wizard comes up after the Home key during the switch, the switch rolls
  back.
- More TV software updaters (CVTE, TCL, Sber, Fire TV, the TCL remote and peripherals updater,
  Sony's camera firmware) are "Software updates" rows, which stay on in Simple.

## 0.3.6 (2026-10-25)

Fixes for TVs that got stuck after a home screen switch rolled back, and for the install that
kept saying the TV stopped answering. Thank you to everyone who sent a report and a log: each of
these fixes starts from one.

### Setup
- A TV whose home screen switch rolled back no longer gets stuck. Setup used to offer "Try with
  the wizard off" after the rollback, which saved a choice every later plan refused, and Back
  led to Welcome instead of the TV's restore options. That card is gone, saved choices the TV
  would refuse are dropped before planning, and Back on a refused plan opens Manage for a TV this
  app already changed (issues #375, #385).
- No more endless "TV stopped answering" during an install. When the TV ends a streamed install
  with a protocol error while still connected, the app checks whether the app landed: done if it
  did, else that step fails and Setup carries on with the clean-up, without the home screen
  switch (issues #80, #364, #367).
- On TCL Google TV sets, the plan screen and Manage > Home screen say before the switch that the
  remote's Settings (gear), Netflix and YouTube buttons don't work with the new home screen, and
  that Use the TV's own home screen brings them back (issue #275).
- A safety rule: the home screen switch is never planned while the TV shows a setup wizard that
  can answer the Home button and the switch would leave it on, also on TVs with an exact list
  (the Sony BRAVIA BF1 list among them). The clean-up alone still runs. Lists whose switch
  switches the wizard off, such as the TCL G10 Google TV list, are unchanged.
- When the TV's port answers but the connection fails, the door now says so, lists what on the
  phone can block the app (a per-app network limit on MIUI or Samsung, a VPN, Private DNS,
  mobile data), and offers Copy error: a short summary with the app and Android version, the
  error and the last log lines, with no addresses in it, ready to paste into an issue.

### Reports and logs
- The problem report lists what this app switched off and has not put back.
- The diagnostics log names why a socket connect failed (the exception class and the system's
  reason), what the TV showed when the home check rolled back, the step the home screen switch
  stopped at, and the in doubt state by name.

## 0.3.5 (2026-10-24)

More TVs get a careful clean-up, and a safety guard for the ACER R4G. Thank you to everyone who
sent a TV report: these changes come straight from them.

### Setup
- More TVs get a careful clean-up. TVs from brands with one or two tested lists (Skyworth, SHARP,
  onn, OnePlus, Haier and others) now switch off up to six hidden feedback and advertising
  services: Google Feedback and its consent prompt, the print service, and Android's on-device
  advertising, ad personalization and ad model services. Each of them is switched off by every
  tested list of that brand and by at least ten lists in all. Before, these TVs got no clean-up
  unless a list matched the exact model (for example issues #89, #108, #169, #194, #196, #251).
  The home screen decision on these TVs is the same as before, and the app still checks each
  switch-off on the TV before it runs it. TVs that already had a brand set get nothing new.
- A safety guard for the ACER R4G. It is made by MOKA, in the same family as the TVs whose setup
  wizard comes back at every restart, so Setup never offers the home screen switch on it, even
  when its setup wizard is off, and does the clean-up alone (issues #74, #102).

### Reports
- A TV report from one of these TVs now says `Profile: brand set <Brand>` instead of `none`.

### Under the hood
- Test builds can carry candidate lists for TVs nobody has tested yet, offered on Setup only to
  people who agree to try one. Releases carry none: this one resolves every TV as it would
  without them.

## 0.3.4 (2026-10-24)

Fixes for the TVs whose home screen was already changed, and an honest message when Setup can't
make a safe plan. Thank you to everyone who sent a report.

### Setup
- A TV whose home screen is already the new home screen installed another way (for example by
  the CleanYourTV tool for computers, which signs it with another key) now gets the clean-up, and
  Setup leaves that home screen as it is. Before, Setup stopped with "plan refused" on every try
  (issues #171, #187, #220). When the home screen this app installed is already in charge, Setup
  knows the switch is already done.
- When Setup can't make a safe plan for a TV, it now says so: the TV answered and nothing was
  changed, and Report a problem sends us the reason. Before, it showed "We couldn't read what's on
  the TV", which said the TV had stopped answering when it hadn't (issues #82, #99, #45).

### Manage
- On a TV whose home screen was installed another way (see Setup above), Home screen says Setup
  left it as it was, as for other home screens that were already changed, instead of offering a
  dock and a look for it (issues #171, #187, #220).

### Reports
- The diagnostics log now names the reasons a plan was refused. Apps you installed yourself are
  not named in them.

## 0.3.3 (2026-10-24)

Fixes from the first public day of issues. Thank you to everyone who reported.

### Setup
- A switch-off the TV answers "yes" to but keeps on is now skipped, and the rest of the clean-up
  runs. Before, Carry on met the same step again every time (issue #10, PatchWall on a Xiaomi TV
  Box S with Android 14). Done names what wouldn't switch off.
- The app and the TV now check the same rule before anything is switched off, so the plan can no
  longer include a step the app itself would then refuse.
- A launcher you installed yourself is never switched off. If the TV's home screen was already
  changed, by another app or an earlier change, Setup leaves it as it is and does the clean-up.
  Before, such a TV got "plan refused" on every try (issue #8).
- On a TV without an exact list, the TV's own home screen now stays switched on during the new
  home screen switch when it is a protected kind of app (one the TV keeps running, its keyboard or
  its screensaver), instead of being switched off.
- When there was nothing to change, Done now says "Nothing was changed" instead of "Your TV is
  ready" (issue #15).
- On a TV the app has no list for, the setup wizard note no longer suggests re-adding the Wi-Fi,
  which would not help there.

### Manage
- Home screen on a TV without the new home screen says why Setup left the TV's own, and what
  would change it, instead of offering a dock and a look that ended in "We stopped" (issue #3).

### Reports
- Help add this TV and Report a problem now fill the GitHub form in for you: the report is part of
  the link when it fits (all the real reports so far do), and is still copied as a fallback. The
  form's report box can no longer be posted empty (issues #12, #14, #16).
- The TV report lists the apps in short groups, so it fits the form.
- Build numbers such as "V816.0.11.0" are no longer mistaken for network addresses and masked.
- Privacy policy: says that when the report is part of the form's link, your browser sends it to
  GitHub as it opens the form. Nothing is posted until you post it.

## 0.3.2 (2026-10-23)

Fixes from the first two issues people reported, and an easy way to report a problem or a new TV.

### Fire TV and undo
- Fire TV sets (and Fire TV Edition TVs from Insignia, Toshiba and others) are now recognised
  from the TV's own details, even when the app has no list for the exact model. They get the
  clean-up only: Fire OS does not let another home screen take over, so the app no longer tries.
  China-market TCL and FFalcon sets without Google services are treated the same way.
- Undo now checks the TV itself. If the TV's own home screen is back, undo finishes, even when
  the TV refuses a step whose result is already in place. Before, a Fire TV could refuse to
  "switch Amazon's home screen back on" although it was never off, and every button (Carry on,
  Undo, Stop here) ran into the same refusal again.
- If you were stuck on "The setup didn't finish" with 0.3.1, it clears the next time the app
  connects to the TV after updating.
- Stop here shows Done only once the run has really stopped, and "Forget this TV" ends an
  unfinished setup for that TV straight away. While setup is still working on a TV, Forget this
  TV and Delete everything ask you to wait or stop it first.
- A TV whose home screen the app does not recognise gets the clean-up only, instead of an error.

### Reporting
- **Help add this TV**: when the app has no list for your TV, one tap makes a short report of
  what the TV is and which apps it came with (apps you installed yourself are only counted, never
  named). You see the whole text first, then copy it into a pre-filled GitHub form or share it
  another way. The app sends nothing itself.
- **Report a problem**, in Settings and on the screens where something went wrong, does the same
  with the redacted log.
- The log is readable: plain error names, how the TV was matched, the home screen decision, and
  the step that failed with the TV's answer.
- "Share diagnostics" said it made a zip; it shares plain text, and the screens now say so.
- The TV report and the problem report never name a home screen app you installed; only the
  TV's own and the app's are named.
- Delete everything now deletes the app's diagnostics log too.
- Privacy policy: the diagnostics section is now "Reports you choose to share", and "What stays
  on your phone" lists the diagnostics log. Nothing new is collected and nothing is sent by the
  app.
- Friendlier issue forms that the app can fill in, and a "Reporting a problem or a new TV"
  section in CONTRIBUTING.

## 0.3.1 (2026-10-23)

A small update for F-Droid's inclusion rules. Nothing changes in how the app cleans a TV.

- Where add-ons are offered (Cards and modules, and Done's "Add a card"), each add-on's page now
  says, before you add it, that add-ons are downloaded from their authors' GitHub releases,
  outside any app store, and are not built or checked by F-Droid. Back leaves without
  downloading anything, as before.
- The store description names the app's internet connections: the version from GitHub and
  F-Droid contacts GitHub only when you tap "Check for updates" or choose to browse and add
  add-ons; the Google Play version makes none of its own. The short summary follows F-Droid's
  style.
- The README says how the app is made: with AI coding agents, under the maintainer's review.
- The app's page and privacy policy moved to https://cleanyour.tv/app/android and
  https://cleanyour.tv/app/android/privacy.

## 0.3.0 (2026-10-24)

A Google Play version of the app, F-Droid readiness, and a website with a privacy policy. Every
channel gets the same app, built from the same code and signed with the same key. On the GitHub
build nothing changes in use.

### Google Play
- A Google Play build of the app, for people who would rather install from the Play Store than
  download an APK (Google Play lists it once its review is done). It has the same app ID, version
  and signing key as the GitHub download.
- The Google Play build is updated by Google Play, so it has no "Check for updates". It downloads
  no add-ons (Cards and modules and Done's "Add a card" are not shown), makes no internet request
  of its own, and always keeps the TV's software updaters on: they are listed under "We keep
  these" instead of being a choice in Customise, and Switched off has no Off choice for them. One
  that an earlier GitHub build switched off is listed in Switched off, where it can be put back.
  Everything else is the same.

### F-Droid
- F-Droid can build the app from source and get exactly the APK published on GitHub, so it can
  offer the same signed APK. The home screen launcher and the TV progress screen that the app
  bundles are rebuilt from source too, and still match the versions the app checks before it
  installs them on a TV.

### Website and privacy policy
- The app has a website, https://udhaykumarbala.github.io/cleanyourtv-app/: the download, the three
  steps, the supported TVs and how to install an APK.
- The privacy policy is at https://udhaykumarbala.github.io/cleanyourtv-app/privacy/ (and in
  PRIVACY.md): the app collects no data and has no accounts, analytics, advertising or crash
  reporting; everything it learns about a TV stays on the phone.

### One app, one key
- The GitHub, F-Droid and Google Play builds share the app ID `tv.cleanyour.app`, the version and
  the signing certificate, so an install from one channel can be updated from another and keeps
  what the app saved.
- One release now builds both reproducibly: the APK for GitHub and F-Droid and the bundle for
  Google Play, each built twice and compared byte for byte, and checked for the same permissions
  and, in the Play bundle, no GitHub host and no update check.

## 0.2.0 (2026-10-21)

Launcher 2.5 and 42 more TV lists (131 in all), with stricter safety: the home screen switch is
skipped up front on TVs where it could break casting, Wi-Fi or start-up, nothing that the TV
inputs, accessibility or the remote need is switched off by default, and the app says why when a TV
keeps its own home screen or an app stays on.

### Home screen launcher
- Bundles the Clean Your TV launcher 2.5 (upstream v2.5.0), built from source and signed with this
  project's launcher key. New on the TV: edit the dock from the remote, choose the rows under the
  dock and a wallpaper in Launcher settings, and more; every new launcher option is off by default.
- A TV set up with 0.1.0 is offered "Update the home screen" in Manage. The dock, the look and the
  card answers stay.
- The Home screen page says what happens to a dock changed on the TV: the phone cannot see it, a
  new look keeps it, and saving a different dock from the phone replaces it. A new look now sends
  the saved dock as it is, even when an app in it has gone from the TV, so the TV's own dock edit
  stays; a dock of more than 12 apps saved under Six columns keeps its first 12 in a four-tile look.
- The phone reads the settings keys launcher 2.5 added exactly as the launcher does.
- Never switches off an app that asks for the TV input hardware permission (the layer under the
  HDMI, AV and tuner inputs), even when a profile lists it and also during the home screen switch,
  where a TV's own home screen that asks for it stays on. The run goes on without it, and Done and
  Manage say it was kept because it runs the TV's HDMI and tuner inputs.
- After a look change, the launcher's remote button services come back on TVs that kept them
  unbound before (accessibility is switched off and on again around the list, as upstream does).
  Stopping a run in the middle of that step finishes it first, so the TV never stays without its
  accessibility services.
- When the home screen switch fails, Setup no longer names a TV's own fallback placeholder as
  another home screen app.

### TV lists
- Matches the TV against 131 community tested profiles from 46 brands, up from 89, from
  the upstream lists of launcher v2.5.0: 42 new ones, among them the first Fire OS 8 list (Fire
  TV Stick 4K, 2023), three more Fire TV devices, six more TCL sets, the Google TV Streamer,
  XGIMI MoGo 2 and MoGo 3 Pro, Xiaomi's China sets on PatchWall and HyperOS 3, Hisense and Vidda
  VIDAA sets, the Freebox Player POP, and first lists from AIWA, BYINTEK, Cecotec, Challenger,
  Compac, EKO, FFalcon, JVC, Kogan, Motorola, MTC, Polytron, SHARP, Tele System and Verizon.
- Sets where the home screen cannot be switched safely get the clean-up and keep their own home,
  and the "What we found" page says why: TVs with a vendor home the app does not know (Xiaomi
  PatchWall, VIDAA, OQEE, Verizon); the Kalley 43G200, the Kalley R10G and the PRISM+ Q65 Ultra,
  one family whose firmware brings the setup wizard back at every boot and has left a set stuck at
  start-up, also when no list matches the TV exactly; and Google's own Chromecast with Google TV and
  Google TV Streamer, where the switch would cost casting after every reboot.
- On TVs whose home screen switch needs the setup wizard off, the switch is skipped up front when
  the wizard saved the TV's Wi-Fi or the TV cannot say who did: switching the wizard off would
  forget the Wi-Fi, and left on, it takes over the Home button and can crash the TV (upstream
  #506). Setup does the clean-up and says how to get the new home screen later. A TV without a list
  gets the clean-up only whenever its setup wizard is on, since the switch would leave the wizard on
  with the TV's own home screen off.
- TCL Live, a TV input, is a choice on every TCL list instead of off by default, and the broadcast
  tuner inputs on the Sony BRAVIA AE2 and PRISM+ Q65 Ultra lists are always kept.
- TalkBack, the phone remote service and the remote's usage logger are a choice on every list that
  names them, never off by default, and a test keeps any accessibility, remote or pairing package
  from being off by default again.
- The app has its real icon, a glass TV, in place of the placeholder outline.
- The app takes its version from the installed APK, so Settings, diagnostics and "Check for
  updates" always name the version that runs.
- Corrections from upstream: the onn 4K box keeps Google's ambient mode so the Google Photos
  screensaver works, the Philips 65PUS8102 also switches off its reboot logger, and the TCL G07 and
  Xiaomi MSSP3 lists gained notes from more units. Home screen customizers that a TV service or
  the stock home's Inputs button relies on are now a choice rather than off by default on four
  more TVs.
- When two profiles match a TV equally, the one whose firmware fingerprint is named more exactly
  wins, so lists for neighbouring firmware builds each reach their own TVs.
- Sixteen upstream lists are not shipped, each with its reason in
  `core/spec/upstream-feedback/unconverted.txt`: duplicates of a shipped list, lists with nothing
  the app can match on, and a TCL China set whose network debugging needs a computer after every
  reboot.

## 0.1.0 (2026-10-20)

The first public release.

### Setup
- Finds Android TV, Google TV and Fire TV devices on the Wi-Fi and guides you through switching on
  network debugging with the remote, with steps per TV family.
- Reads the TV and matches it against 89 community tested profiles (27 brands), with a careful
  brand fallback for models nobody has profiled yet.
- Shows what it would switch off, grouped and counted, and what it keeps, before anything runs;
  Customise changes the list, the dock and the look, and shows every command in order.
- "Make it clean": saves a restore point, disables the clutter (never uninstalls), installs the
  Clean Your TV launcher 2.4 with a dock of the apps you use, and shows a progress animation on the
  TV while it works. A watchdog on the TV puts its own launcher back if the phone does not confirm
  the new home screen within 150 seconds.
- Asks for the phone's screen lock once per session before the first change; switches network
  debugging off again at the end unless you keep it on.

### Afterwards
- Manage: put back any switched off app, check for leftovers after a TV software update, change
  the home screen, cards and modules, undo everything from the restore point.
- Save a copy of the restore point as a zip with plain restore scripts that work from any computer
  with adb.
- Optional add-on apps for the TV from a signed list, checked by size, SHA-256 and signer before
  install.
- Settings: check for updates (one request to GitHub, on demand), share redacted diagnostics after
  a preview, licences.

### Under the hood
- No accounts, no analytics; internet only to GitHub and only on your action.
- Reproducible release build: two clean container builds byte identical; CI builds the same
  unsigned APK for every tag so the signed release can be checked against it.
- Signed with the release key, certificate SHA-256
  `eaf7af3867bb29ff4bb038854842c93f42ffe4198a087e611cb18f925201cd65`.
