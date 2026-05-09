/** @type {import('jest').Config} */
module.exports = {
  // ESM-flavored project (package.json type:module). Use ts-jest with the
  // ESM preset so .test.ts files import the .js-extension paths they
  // reference at runtime.
  preset: "ts-jest/presets/default-esm",
  testEnvironment: "node",
  extensionsToTreatAsEsm: [".ts"],
  // Map .js → .ts for source imports — TypeScript NodeNext modules
  // require explicit .js extensions in import paths, but jest needs to
  // resolve to the .ts source file.
  moduleNameMapper: {
    "^(\\.{1,2}/.*)\\.js$": "$1",
  },
  transform: {
    "^.+\\.tsx?$": [
      "ts-jest",
      {
        useESM: true,
        tsconfig: { module: "esnext", target: "ES2022", strict: true, esModuleInterop: true, skipLibCheck: true },
      },
    ],
  },
  testMatch: ["**/__tests__/**/*.test.ts"],
  collectCoverageFrom: ["src/**/*.ts", "!src/**/*.d.ts"],
};
