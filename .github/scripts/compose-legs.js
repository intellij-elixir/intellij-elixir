'use strict';

// Composes the test and plugin-verification legs from .github/ci-versions.json. It needs a job of its
// own because a strategy.matrix expression can read the github, needs, vars and inputs contexts only -
// never a file.
//
// `verify` is not projected onto a test leg: every test leg builds against IntelliJ IDEA
// (platformType=IU), and those products are verification targets only.

const { readDeclaration, ideaVersions, baseline, beamAdditional, verifyProducts } = require('./ci-versions');
const { setOutput, addSummary, fail } = require('./actions');

const declaration = readDeclaration();

const minimumSupported = declaration.idea.minimumSupported;
const base = baseline(declaration);

// The label is the only name used downstream - the job, both artifacts, and the check named after the
// Test Results artifact - so a leg group names the axis it varies and omits what is invariant within
// it. Two constraints on the format: the discriminator goes first, because the checks graph truncates
// names at roughly 24 characters, and `/` is illegal in an artifact name (hence `+` between Elixir and
// OTP) though legal in a job name.
const leg = (os, idea, beam, label) => ({
  os,
  'idea-version': idea.version,
  'java-version': idea.java,
  beam,
  label,
});

const linuxRunners = ['blacksmith-2vcpu-ubuntu-2204', 'blacksmith-4vcpu-ubuntu-2204', 'blacksmith-8vcpu-ubuntu-2204'];
const linuxRunner = (index) => linuxRunners[index % linuxRunners.length];
const ideaLegs = ideaVersions(declaration);

const legs = [
  ...ideaLegs.map((idea, index) => leg(linuxRunner(index), idea, base, `IDEA ${idea.version}`)),
  ...beamAdditional(declaration).map((beam, index) =>
    leg(linuxRunner(ideaLegs.length + index), minimumSupported, beam, `${beam.elixir}+${beam.otp}`),
  ),
  leg('windows-2025', minimumSupported, base, `Win25, IDEA ${minimumSupported.version}`),
];

// One leg per product x version, never several IDEs per verifier JVM - see shared-verify.yml.
const verifyLegs = ideaVersions(declaration).flatMap((idea) =>
  verifyProducts(idea).map((product) => ({ product, version: idea.version })),
);

// Rejects rather than deduplicates: upload-artifact refuses the second of a duplicate name, so a
// silently dropped leg leaves the aggregate reporting a smaller matrix as if that were all of it.
// Run before the matrix is emitted, so a collision costs a two-second job instead of twelve minutes.
function requireUniqueNames(names, title, consequence) {
  const duplicates = [...new Set(names.filter((name, index) => names.indexOf(name) !== index))];
  if (!duplicates.length) return;
  fail(
    `${duplicates.join(', ')} - ${consequence}. Remove the duplicate from .github/ci-versions.json,` +
      ' or widen the label in compose-legs.js if the entries differ in a field it leaves out.',
    title,
  );
}

requireUniqueNames(
  legs.map((entry) => entry.label),
  'Duplicate test legs',
  'two test legs would share a job name, a check name and both artifact names',
);
requireUniqueNames(
  verifyLegs.map((entry) => `${entry.product} ${entry.version}`),
  'Duplicate verification legs',
  'two verification jobs would share a name and a report artifact name',
);

setOutput('matrix', JSON.stringify({ include: legs }));
setOutput('verify', JSON.stringify({ include: verifyLegs }));
// An empty include list is not a valid matrix, so the verify job is gated on this instead.
setOutput('verify-any', verifyLegs.length > 0 ? 'true' : 'false');

addSummary(
  [
    '### Test legs',
    '',
    '| leg | os | IDEA | JBR | Elixir | OTP | informational |',
    '| --- | --- | --- | --- | --- | --- | --- |',
    ...legs.map((entry) =>
      [
        '',
        entry.label,
        entry.os,
        entry['idea-version'],
        entry['java-version'],
        entry.beam.elixir,
        entry.beam.otp,
        entry.beam['continue-on-error'] || false,
        '',
      ].join(' | ').trim(),
    ),
    '',
    '### Verification legs',
    '',
    '| product | version |',
    '| --- | --- |',
    ...verifyLegs.map((entry) => `| ${entry.product} | ${entry.version} |`),
    '',
  ].join('\n'),
);
