#
# To learn more about a Podspec see http://guides.cocoapods.org/syntax/podspec.html.
# Run `pod lib lint flutter_deeplinkly.podspec` to validate before publishing.
#
Pod::Spec.new do |s|
  s.name             = 'flutter_deeplinkly'
  s.version          = '1.9.0'
  s.summary          = 'Flutter Deeplinkly SDK'
  s.description      = <<-DESC
Flutter Deeplinking Project
                       DESC
  s.homepage         = 'https://deeplinkly.com'
  s.license          = { :file => '../LICENSE' }
  s.author           = { 'Deeplinkly' => 'hello@deeplinkly.com' }
  s.source           = { :path => '.' }
  s.source_files = 'Classes/**/*'
  s.dependency 'Flutter'

  # Stays at 12.0. ATTrackingManager and ASIdentifierManager are iOS 14 at
  # *runtime*, not at deployment target: they are weak-linked and guarded with
  # `if #available(iOS 14.0, *)`, so raising this would drop support for iOS
  # 12/13 host apps in exchange for nothing.
  s.platform = :ios, '12.0'

  # Weak-linked. AdSupport and AppTrackingTransparency are only entered when a
  # host app opts into IDFA collection (Info.plist DeeplinklyEnableIDFA); an app
  # that does not never executes that code, and reading the ATT *status* alone
  # is not tracking and needs no prompt.
  s.weak_frameworks = 'AdSupport', 'AppTrackingTransparency'

  # Flutter.framework does not contain a i386 slice.
  s.pod_target_xcconfig = { 'DEFINES_MODULE' => 'YES', 'EXCLUDED_ARCHS[sdk=iphonesimulator*]' => 'i386' }
  s.swift_version = '5.0'

  # Required: the SDK uses UserDefaults, ProcessInfo.systemUptime, file
  # timestamps and disk space, all of which are required-reason APIs. Without
  # this line the manifest is never bundled and App Store Connect rejects the
  # upload.
  # https://developer.apple.com/documentation/bundleresources/privacy_manifest_files
  #
  # This bundles the DEFAULT, non-tracking manifest only. Apps that set
  # DeeplinklyEnableIDFA must merge Resources/IDFA/PrivacyInfo.xcprivacy into
  # their own manifest — see the notes in that file for why it is not shipped
  # here.
  s.resource_bundles = {'flutter_deeplinkly_privacy' => ['Resources/PrivacyInfo.xcprivacy']}
end
