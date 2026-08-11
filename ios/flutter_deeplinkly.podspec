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
  s.dependency 'Deeplinkly', '1.9.0'

  # Stays at 12.0. ATTrackingManager and ASIdentifierManager are iOS 14 at
  # *runtime*, not at deployment target: they are weak-linked and guarded with
  # `if #available(iOS 14.0, *)`, so raising this would drop support for iOS
  # 12/13 host apps in exchange for nothing.
  s.platform = :ios, '12.0'

  # Flutter.framework does not contain a i386 slice.
  s.pod_target_xcconfig = { 'DEFINES_MODULE' => 'YES', 'EXCLUDED_ARCHS[sdk=iphonesimulator*]' => 'i386' }
  s.swift_version = '5.0'
end
