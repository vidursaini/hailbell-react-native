// Metro reads @hailbell/react-native from the folder above (a file: dependency). Autolinking module
// resolution (app.json `experiments.autolinkingModuleResolution`) then resolves react, react-native
// and expo to this app's copies, not the ones in ../node_modules.
const { getDefaultConfig } = require('expo/metro-config');
const path = require('path');

const config = getDefaultConfig(__dirname);
config.watchFolders = [path.resolve(__dirname, '..')];

module.exports = config;
