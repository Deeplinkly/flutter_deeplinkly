#!/usr/bin/env ruby
# Syncs the RunnerTests target with the .swift files under RunnerTests/.
#
# The project uses explicit PBXFileReferences rather than a synchronized folder
# group, so a file dropped into the directory is not compiled until it is
# registered here, and a deleted one breaks the build until it is unregistered.
# Adds what is missing, prunes what is gone. Idempotent.
#
#   ruby add_test_files.rb
require 'xcodeproj'

project_path = File.expand_path('Runner.xcodeproj', __dir__)
project = Xcodeproj::Project.open(project_path)

target = project.targets.find { |t| t.name == 'RunnerTests' }
raise 'RunnerTests target not found' unless target

group = project.main_group['RunnerTests']
raise 'RunnerTests group not found' unless group

existing = target.source_build_phase.files.map { |f| f.file_ref && f.file_ref.path }.compact

added = []
Dir.glob(File.expand_path('RunnerTests/*.swift', __dir__)).sort.each do |path|
  name = File.basename(path)
  next if existing.include?(name)

  ref = group.files.find { |f| f.path == name } || group.new_reference(name)
  target.add_file_references([ref])
  added << name
end

on_disk = Dir.glob(File.expand_path('RunnerTests/*.swift', __dir__)).map { |p| File.basename(p) }

removed = []
target.source_build_phase.files.to_a.each do |build_file|
  ref = build_file.file_ref
  next unless ref
  name = ref.path
  next unless name.to_s.end_with?('.swift')
  next if on_disk.include?(name)

  build_file.remove_from_project
  ref.remove_from_project
  removed << name
end

if added.empty? && removed.empty?
  puts 'RunnerTests is already in sync.'
else
  project.save
  puts "Added: #{added.join(', ')}" unless added.empty?
  puts "Removed: #{removed.join(', ')}" unless removed.empty?
end
