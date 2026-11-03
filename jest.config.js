module.exports = {
  projects: [
    {
      displayName: 'js',
      preset: 'jest-expo/ios',
      roots: ['<rootDir>/src'],
    },
    {
      displayName: 'plugin',
      testEnvironment: 'node',
      roots: ['<rootDir>/plugin'],
      testPathIgnorePatterns: ['/build/', '/vendor/'],
      transform: { '\\.[jt]s$': 'babel-jest' },
    },
  ],
};
