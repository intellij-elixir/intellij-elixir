'use strict';
// Head sha of the most recent successful run of this workflow for this ref, or empty when there is
// none. Consumed by code-changed.js, which treats empty as "run the tests".
//
// Gate contract matches code-changed.js: every failure here answers empty rather than throwing, so a
// lookup problem costs a matrix run and never skips one.

const { execFileSync } = require('child_process');
const { setOutput, warn } = require('./actions.js');
const { isFullRun } = require('./run-events.js');

function gh(endpoint) {
  const out = execFileSync('gh', ['api', endpoint], { encoding: 'utf8', maxBuffer: 1 << 28 });
  return JSON.parse(out);
}

let sha = '';
let runId = '';
try {
  const repo = process.env.REPO;
  const branch = process.env.BRANCH;
  // Named apart from the `runId` being reported: shadowing it here made the assignment below a
  // write to a const, which threw and left run_id empty while sha still looked right.
  const currentRunId = process.env.RUN_ID;
  // The workflow this run belongs to, so the lookup follows a rename of the file or of `name:`.
  const workflowId = gh(`repos/${repo}/actions/runs/${currentRunId}`).workflow_id;
  const runs = gh(
    `repos/${repo}/actions/workflows/${workflowId}/runs` +
    `?branch=${encodeURIComponent(branch)}&status=success&per_page=20`
  ).workflow_runs || [];
  // A branch filter also matches a fork pull request opened from a branch of the same name, and that run
  // tested only the subset: a run that tests everything takes a previous run that did too.
  const wantFullRun = isFullRun(process.env.EVENT_NAME);
  // Newest first; skip this run and anything that has not concluded.
  const previous = runs.find(r =>
    String(r.id) !== String(currentRunId) && r.conclusion === 'success' && (!wantFullRun || isFullRun(r.event)));
  if (previous) {
    sha = previous.head_sha;
    runId = String(previous.id);
  }
  console.log(previous
    ? `last successful run: ${previous.id} at ${previous.head_sha.slice(0, 9)}`
    : `no previous successful run for ${branch}`);
} catch (e) {
  warn(`could not look up the last successful run, tests will run: ${e.message.split('\n')[0]}`);
}

setOutput('sha', sha);
// The run itself, for callers that want its artifacts rather than just its tree.
setOutput('run_id', runId);
