require 'json'

package = JSON.parse(File.read(File.join(__dir__, '..', 'package.json')))

Pod::Spec.new do |s|
  s.name           = 'HailbellReactNative'
  s.version        = package['version']
  s.summary        = package['description']
  s.description    = package['description']
  s.license        = { type: 'MIT', file: '../LICENSE' }
  s.author         = 'Hailbell'
  s.homepage       = package['homepage']
  # The Swift SDK supports iOS 16 and later.
  s.platforms      = { ios: '16.0' }
  s.swift_version  = '5.9'
  s.source         = { git: 'https://github.com/vidursaini/hailbell-react-native.git', tag: package['version'] }
  s.static_framework = true

  s.dependency 'ExpoModulesCore'

  s.pod_target_xcconfig = {
    'DEFINES_MODULE' => 'YES',
  }

  # The bridge, plus the Swift SDK copied from sdk/swift by scripts/sync-native.mjs. Both compile into
  # this one module, so the bridge uses the SDK without an import.
  s.source_files = '*.swift', 'Vendor/Hailbell/**/*.swift'
  # The SDK's privacy manifest, which Xcode merges into the app's privacy report.
  s.resource_bundles = { 'HailbellReactNative_privacy' => ['Vendor/Hailbell/PrivacyInfo.xcprivacy'] }
  s.frameworks = 'UIKit', 'SwiftUI', 'UserNotifications', 'Combine', 'PhotosUI', 'Security'
end
