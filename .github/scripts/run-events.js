'use strict';

// Which events test only a subset of the matrix. Every other event tests every leg, so an event nobody
// listed here gets the most, not the least. compose-legs.js composes by it and last-successful-run.js
// finds a baseline by it, so the two cannot disagree about what a full run is.

const SUBSET_EVENTS = new Set(['pull_request', 'push']);

const isFullRun = (event) => !SUBSET_EVENTS.has(event);

module.exports = { isFullRun };
