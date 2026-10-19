// SonarCloud reads the paths of an lcov report from the repository root, and vitest writes them from ui/ (`src/config.ts`), so
// every file would count as uncovered. This writes the same report with the paths from the root, for the analysis.
import { readFileSync, writeFileSync } from 'node:fs';

const report = readFileSync('coverage/lcov.info', 'utf8');
writeFileSync('coverage/lcov-sonar.info', report.replace(/^SF:(.*)$/gm, 'SF:ui/$1'));
console.log('lcov for sonar: coverage/lcov-sonar.info');
