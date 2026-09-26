'use strict';

// The leg's own verdict, for test-results.yml to report. It can only come from inside the job: with
// step-level continue-on-error the jobs API calls the whole job successful, and the failure exists only
// in each step's `outcome`, which that API does not expose.

const fs = require('fs');
const { setOutput, warn } = require('./actions');

// Stage order matters: the EARLIEST failure describes the leg. continue-on-error does not stop the job,
// so an informational leg that fails to compile fails every later stage too.
const STAGES = [
  ['SETUP_ENV_OUTCOME', 'toolchain setup'],
  ['COMPILE_OUTCOME', 'compilation'],
  ['SANDBOX_OUTCOME', 'test sandbox'],
  ['QUOTER_OUTCOME', 'quoter build'],
  ['CORPUS_OUTCOME', 'corpus download'],
  ['TEST_OUTCOME', 'tests'],
];

try {
  // Not just `failure`: stages after a hard failure are `skipped`, and exceeding timeout-minutes leaves
  // the running one `cancelled`. Either counting as success is how a hung leg reports as passing.
  // `not run` is a stage this leg's suite does not have, which the workflow states explicitly.
  const failure = STAGES.find(([variable]) => !['success', 'not run'].includes(process.env[variable]));
  const failedAt = failure ? failure[1] : null;

  // Tests that reached a verdict leave complete XMLs, so a leg that failed them is still comparable
  // with a green one. A leg that died earlier, or was cut off by the timeout, is comparable with none.
  const testOutcome = process.env.TEST_OUTCOME;
  const testsRan =
    (testOutcome === 'success' || testOutcome === 'failure') &&
    (failedAt === null || failedAt === 'tests');

  // Inside the parentheses: test-results.yml downloads with pattern 'Test Results (*)', and minimatch
  // needs the name to end in ')', so a `[...]` suffix is dropped from the aggregate without complaint.
  const label = process.env.LABEL;
  const marker = !testsRan && failedAt ? `, INCOMPLETE - failed at ${failedAt}` : '';

  setOutput('failed-at', failedAt || '');
  setOutput('tests-ran', testsRan);
  setOutput('artifact-name', `Test Results (${label}${marker})`);

  const status = {
    label,
    os: process.env.LEG_OS,
    idea: process.env.IDEA,
    elixir: process.env.ELIXIR,
    otp: process.env.OTP,
    informational: process.env.INFORMATIONAL === 'true',
    failed_at: failedAt,
    tests_ran: testsRan,
  };
  fs.writeFileSync('leg-status.json', `${JSON.stringify(status, null, 2)}\n`, 'utf8');
  console.log(JSON.stringify(status, null, 2));
} catch (error) {
  // Reporting must never redden a leg: the renderer marks a leg with no status file unknown, and Upload
  // Test Results falls back to the unmarked artifact name.
  warn(`could not record leg status: ${error.message}`, 'Leg status not recorded');
}
