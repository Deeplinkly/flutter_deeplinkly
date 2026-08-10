#
# To learn more about a Podspec see http://guides.cocoapods.org/syntax/podspec.html.
# Run `pod lib lint flutter_deeplinkly.podspec` to validate before publishing.
#
Pod::Spec.new do |s|
  s.name             = 'flutter_deeplinkly'
  s.version          = '1.8.0'
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
  s.platform = :ios, '12.0'

  # Flutter.framework does not contain a i386 slice.
  s.pod_target_xcconfig = { 'DEFINES_MODULE' => 'YES', 'EXCLUDED_ARCHS[sdk=iphonesimulator*]' => 'i386' }
  s.swift_version = '5.0'

  # Required: the SDK uses UserDefaults and ProcessInfo.systemUptime, both of
  # which are required-reason APIs. Without this line the manifest is never
  # bundled and App Store Connect rejects the upload.
  # https://developer.apple.com/documentation/bundleresources/privacy_manifest_files
  s.resource_bundles = {'flutter_deeplinkly_privacy' => ['Resources/PrivacyInfo.xcprivacy']}
end
