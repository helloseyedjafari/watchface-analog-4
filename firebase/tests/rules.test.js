import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { after, before, beforeEach, describe, it } from 'node:test';
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} from '@firebase/rules-unit-testing';
import { collection, deleteDoc, doc, getDoc, getDocs, serverTimestamp, setDoc, Timestamp } from 'firebase/firestore';

const PROJECT = 'demo-analogplus';
const ID = '0123456789abcdef0123456789abcdef';
let env;

const design = (over = {}) => ({
  name: 'Mine', design: '{"name":"Mine"}', updatedAt: serverTimestamp(), deleted: false, schema: 1, ...over,
});
const current = (over = {}) => ({
  design: '{"name":"Mine"}', updatedAt: serverTimestamp(), deviceId: 'dev-1', deviceLabel: 'Pixel 8', schema: 1, ...over,
});
const WRITE_ID = '0b1c2d3e-4f50-4162-8374-95a6b7c8d9e0';
const backup = (over = {}) => ({
  sealed: 'AAAA', kdf: 'pbkdf2-hmac-sha256', iterations: 600000, salt: 'c2FsdHNhbHRzYWx0c2FsdA==',
  iv: 'aXZpdml2aXZpdml2', version: 1, createdAt: serverTimestamp(), keyFingerprint: '0123456789abcdef', writeId: WRITE_ID, ...over,
});

before(async () => {
  env = await initializeTestEnvironment({
    projectId: PROJECT,
    firestore: { rules: readFileSync(new URL('../firestore.rules', import.meta.url), 'utf8') },
  });
});
after(async () => { await env.cleanup(); });
beforeEach(async () => { await env.clearFirestore(); });

// The account marker (the kill switch): seeded with the rules switched off, live (`deleted: false`) unless told otherwise.
const seedMarker = (uid = 'alice', deleted = false) => env.withSecurityRulesDisabled((c) =>
  setDoc(doc(c.firestore(), `users/${uid}/meta/account`), { deleted, updatedAt: Timestamp.now(), schema: 1 }));
const marker = (over = {}) => ({ deleted: false, updatedAt: serverTimestamp(), schema: 1, ...over });
const live = () => seedMarker('alice', false);

const alice = () => env.authenticatedContext('alice').firestore();
const bob = () => env.authenticatedContext('bob').firestore();
const anon = () => env.unauthenticatedContext().firestore();

// Documents seeded with the rules switched off, so the read/list/delete tests see data that really exists.
const seedAlice = () => env.withSecurityRulesDisabled(async (c) => {
  const db = c.firestore();
  await setDoc(doc(db, 'users/alice/state/current'), { design: '{}', updatedAt: Timestamp.now(), deviceId: 'd', deviceLabel: '', schema: 1 });
  await setDoc(doc(db, 'users/alice/backup/signingKey'), {
    sealed: 'AAAA', kdf: 'pbkdf2-hmac-sha256', iterations: 600000, salt: 'c2FsdHNhbHRzYWx0c2FsdA==',
    iv: 'aXZpdml2aXZpdml2', version: 1, createdAt: Timestamp.now(), keyFingerprint: '0123456789abcdef', writeId: WRITE_ID,
  });
});

describe('designs', () => {
  beforeEach(live);
  it('lets the owner create, read, update and delete', async () => {
    const ref = doc(alice(), `users/alice/designs/${ID}`);
    await assertSucceeds(setDoc(ref, design()));
    await assertSucceeds(getDoc(ref));
    await assertSucceeds(setDoc(ref, design({ name: 'Renamed' })));
    await assertSucceeds(deleteDoc(ref));
  });
  it('accepts a tombstone with an empty design', async () => {
    await assertSucceeds(setDoc(doc(alice(), `users/alice/designs/${ID}`), design({ design: '', deleted: true })));
  });
  it('rejects a live design with an empty design string', async () => {
    await assertFails(setDoc(doc(alice(), `users/alice/designs/${ID}`), design({ design: '' })));
  });
  it('denies another signed-in user, reading and writing', async () => {
    await env.withSecurityRulesDisabled((c) => setDoc(doc(c.firestore(), `users/alice/designs/${ID}`), { name: 'x', design: '{}', updatedAt: Timestamp.now(), deleted: false, schema: 1 }));
    await assertFails(getDoc(doc(bob(), `users/alice/designs/${ID}`)));
    await assertFails(setDoc(doc(bob(), `users/alice/designs/${ID}`), design()));
    await assertFails(deleteDoc(doc(bob(), `users/alice/designs/${ID}`)));
  });
  it('denies listing another user\'s designs', async () => {
    await assertFails(getDocs(collection(bob(), 'users/alice/designs')));
    await assertSucceeds(getDocs(collection(alice(), 'users/alice/designs')));
  });
  it('denies unauthenticated access', async () => {
    await assertFails(getDoc(doc(anon(), `users/alice/designs/${ID}`)));
    await assertFails(setDoc(doc(anon(), `users/alice/designs/${ID}`), design()));
  });
  it('rejects a client-supplied updatedAt', async () => {
    await assertFails(setDoc(doc(alice(), `users/alice/designs/${ID}`), design({ updatedAt: Timestamp.fromMillis(1) })));
  });
  it('rejects an oversized design string', async () => {
    await assertFails(setDoc(doc(alice(), `users/alice/designs/${ID}`), design({ design: 'x'.repeat(950001) })));
    await assertSucceeds(setDoc(doc(alice(), `users/alice/designs/${ID}`), design({ design: 'x'.repeat(900000) })));
  });
  it('rejects malformed documents', async () => {
    const ref = doc(alice(), `users/alice/designs/${ID}`);
    await assertFails(setDoc(ref, design({ extra: 1 })));
    await assertFails(setDoc(ref, design({ name: '' })));
    await assertFails(setDoc(ref, design({ name: 'n'.repeat(101) })));
    await assertFails(setDoc(ref, design({ schema: '1' })));
    await assertFails(setDoc(ref, design({ schema: 0 })));
    await assertFails(setDoc(ref, design({ deleted: 'no' })));
    const { name, ...missing } = design();
    await assertFails(setDoc(ref, missing));
  });
  it('rejects a tombstone that still carries the design payload', async () => {
    await assertFails(setDoc(doc(alice(), `users/alice/designs/${ID}`), design({ deleted: true })));
  });
  it('rejects a whitespace-only name', async () => {
    await assertFails(setDoc(doc(alice(), `users/alice/designs/${ID}`), design({ name: '   ' })));
  });
  it('counts the name in characters, not bytes: 100 two-byte characters pass, 101 do not', async () => {
    const ref = doc(alice(), `users/alice/designs/${ID}`);
    await assertSucceeds(setDoc(ref, design({ name: 'é'.repeat(100) })));
    await assertFails(setDoc(ref, design({ name: 'é'.repeat(101) })));
  });
  it('counts the name in UTF-16 units, as the app does: 50 emoji pass, 51 do not', async () => {
    // an emoji is one code point but two UTF-16 units; the rules' size() counts units (the app's MAX_DESIGN_NAME_CHARS
    // does too, SyncEngine.nameTooLong): 100 emoji, which a code-point count would allow, are refused
    const ref = doc(alice(), `users/alice/designs/${ID}`);
    await assertSucceeds(setDoc(ref, design({ name: '😀'.repeat(50) })));
    await assertFails(setDoc(ref, design({ name: '😀'.repeat(51) })));
    await assertFails(setDoc(ref, design({ name: '😀'.repeat(100) })));
  });
  it('accepts schema 1000 and rejects 1001', async () => {
    const ref = doc(alice(), `users/alice/designs/${ID}`);
    await assertSucceeds(setDoc(ref, design({ schema: 1000 })));
    await assertFails(setDoc(ref, design({ schema: 1001 })));
  });
  it('rejects a design id that is not 32 lowercase hex characters', async () => {
    await assertFails(setDoc(doc(alice(), 'users/alice/designs/not-an-id'), design()));
    await assertFails(setDoc(doc(alice(), `users/alice/designs/${ID.toUpperCase()}`), design()));
  });
});

describe('current design', () => {
  beforeEach(live);
  it('lets the owner write and read state/current only', async () => {
    const ref = doc(alice(), 'users/alice/state/current');
    await assertSucceeds(setDoc(ref, current()));
    await assertSucceeds(getDoc(ref));
    await assertFails(setDoc(doc(alice(), 'users/alice/state/other'), current()));
  });
  it('accepts the longest device id (64) and device label (80)', async () => {
    const ref = doc(alice(), 'users/alice/state/current');
    await assertSucceeds(setDoc(ref, current({ deviceId: 'd'.repeat(64), deviceLabel: 'l'.repeat(80) })));
    await assertSucceeds(setDoc(ref, current({ deviceLabel: '' })));
  });
  it('lets the owner delete the current design (the account wipe needs it)', async () => {
    await seedAlice();
    await assertSucceeds(deleteDoc(doc(alice(), 'users/alice/state/current')));
  });
  it('rejects bad fields and other users', async () => {
    await assertFails(setDoc(doc(alice(), 'users/alice/state/current'), current({ deviceId: '' })));
    await assertFails(setDoc(doc(alice(), 'users/alice/state/current'), current({ design: 'x'.repeat(950001) })));
    await assertFails(setDoc(doc(bob(), 'users/alice/state/current'), current()));
  });
  it('denies another signed-in user reading, listing or deleting the current design', async () => {
    await seedAlice();
    await assertFails(getDoc(doc(bob(), 'users/alice/state/current')));
    await assertFails(getDocs(collection(bob(), 'users/alice/state')));
    await assertFails(deleteDoc(doc(bob(), 'users/alice/state/current')));
    await assertSucceeds(getDoc(doc(alice(), 'users/alice/state/current')));
  });
  it('rejects a client-supplied updatedAt', async () => {
    await assertFails(setDoc(doc(alice(), 'users/alice/state/current'), current({ updatedAt: Timestamp.fromMillis(1) })));
  });
  it('rejects extra, missing and mistyped fields', async () => {
    const ref = doc(alice(), 'users/alice/state/current');
    await assertFails(setDoc(ref, current({ extra: 1 })));
    await assertFails(setDoc(ref, current({ schema: '1' })));
    await assertFails(setDoc(ref, current({ schema: 0 })));
    await assertFails(setDoc(ref, current({ schema: 1001 })));
    await assertFails(setDoc(ref, current({ deviceLabel: 'l'.repeat(81) })));
    await assertFails(setDoc(ref, current({ deviceId: 'd'.repeat(65) })));
    const { deviceLabel, ...missing } = current();
    await assertFails(setDoc(ref, missing));
  });
});

describe('signing key backup', () => {
  beforeEach(live);
  it('lets the owner write and read backup/signingKey only', async () => {
    const ref = doc(alice(), 'users/alice/backup/signingKey');
    await assertSucceeds(setDoc(ref, backup()));
    await assertSucceeds(getDoc(ref));
    await assertSucceeds(deleteDoc(ref));
    await assertFails(setDoc(doc(alice(), 'users/alice/backup/second'), backup()));
  });
  it('denies other users and anonymous callers', async () => {
    await assertFails(setDoc(doc(bob(), 'users/alice/backup/signingKey'), backup()));
    await assertFails(getDoc(doc(anon(), 'users/alice/backup/signingKey')));
  });
  it('denies another signed-in user reading, listing or deleting the backup', async () => {
    await seedAlice();
    await assertFails(getDoc(doc(bob(), 'users/alice/backup/signingKey')));
    await assertFails(getDocs(collection(bob(), 'users/alice/backup')));
    await assertFails(deleteDoc(doc(bob(), 'users/alice/backup/signingKey')));
    await assertSucceeds(getDoc(doc(alice(), 'users/alice/backup/signingKey')));
  });
  it('rejects salt, iv and version outside their bounds', async () => {
    const ref = doc(alice(), 'users/alice/backup/signingKey');
    await assertFails(setDoc(ref, backup({ salt: 's'.repeat(15) })));
    await assertFails(setDoc(ref, backup({ salt: 's'.repeat(129) })));
    await assertFails(setDoc(ref, backup({ iv: 'i'.repeat(15) })));
    await assertFails(setDoc(ref, backup({ iv: 'i'.repeat(65) })));
    await assertFails(setDoc(ref, backup({ version: 0 })));
    await assertFails(setDoc(ref, backup({ version: 1001 })));
    await assertFails(setDoc(ref, backup({ version: '1' })));
  });
  it('rejects a fingerprint that is not 16 lowercase hex characters', async () => {
    const ref = doc(alice(), 'users/alice/backup/signingKey');
    await assertFails(setDoc(ref, backup({ keyFingerprint: 'zzzzzzzzzzzzzzzz' })));
    await assertFails(setDoc(ref, backup({ keyFingerprint: '0123456789ABCDEF' })));
    await assertFails(setDoc(ref, backup({ keyFingerprint: '0123456789abcdef0' })));
  });
  it('rejects missing fields', async () => {
    const { iv, ...missing } = backup();
    await assertFails(setDoc(doc(alice(), 'users/alice/backup/signingKey'), missing));
  });
  it('requires a writeId', async () => {
    const { writeId, ...missing } = backup();
    await assertFails(setDoc(doc(alice(), 'users/alice/backup/signingKey'), missing));
  });
  it('accepts a lowercase UUID as writeId', async () => {
    const ref = doc(alice(), 'users/alice/backup/signingKey');
    await assertSucceeds(setDoc(ref, backup({ writeId: '00000000-0000-4000-8000-000000000000' })));
    await assertSucceeds(setDoc(ref, backup({ writeId: 'ffffffff-ffff-4fff-bfff-ffffffffffff' })));
  });
  it('rejects a writeId that is not a lowercase UUID', async () => {
    const ref = doc(alice(), 'users/alice/backup/signingKey');
    for (const bad of [
      '', 'x', 'not-a-uuid', WRITE_ID.toUpperCase(), WRITE_ID.replaceAll('-', ''), WRITE_ID + '0', ' ' + WRITE_ID,
      '0b1c2d3e-4f50-4162-8374-95a6b7c8d9eg', '0b1c2d3e-4f50-4162-8374-95a6b7c8d9e', '0b1c2d3e4-f50-4162-8374-95a6b7c8d9e0',
    ]) await assertFails(setDoc(ref, backup({ writeId: bad })));
    await assertFails(setDoc(ref, backup({ writeId: 12345 })));
    await assertFails(setDoc(ref, backup({ writeId: null })));
  });
  it('still rejects extra fields next to a valid writeId', async () => {
    await assertFails(setDoc(doc(alice(), 'users/alice/backup/signingKey'), backup({ extra: 'x' })));
  });
  it('rejects weak or unknown key derivation and malformed fields', async () => {
    const ref = doc(alice(), 'users/alice/backup/signingKey');
    await assertFails(setDoc(ref, backup({ iterations: 1000 })));
    await assertFails(setDoc(ref, backup({ iterations: 6000000 })));
    await assertFails(setDoc(ref, backup({ kdf: 'md5' })));
    await assertFails(setDoc(ref, backup({ keyFingerprint: 'short' })));
    await assertFails(setDoc(ref, backup({ sealed: 'A'.repeat(16385) })));
    await assertFails(setDoc(ref, backup({ createdAt: Timestamp.fromMillis(1) })));
    await assertFails(setDoc(ref, backup({ extra: true })));
  });
});

describe('everything else', () => {
  it('denies the user document itself and unknown collections', async () => {
    await assertFails(setDoc(doc(alice(), 'users/alice'), { a: 1 }));
    await assertFails(setDoc(doc(alice(), 'users/alice/other/x'), { a: 1 }));
    await assertFails(setDoc(doc(alice(), 'public/x'), { a: 1 }));
    await assertFails(getDoc(doc(alice(), 'public/x')));
    await assertFails(getDocs(collection(alice(), 'users')));
  });
});

describe('account marker and the kill switch', () => {
  const markerRef = () => doc(alice(), 'users/alice/meta/account');
  const designRef = () => doc(alice(), `users/alice/designs/${ID}`);
  const currentRef = () => doc(alice(), 'users/alice/state/current');
  const backupRef = () => doc(alice(), 'users/alice/backup/signingKey');

  it('refuses writes of designs, the current design and the backup while no marker exists', async () => {
    await assertFails(setDoc(designRef(), design()));
    await assertFails(setDoc(currentRef(), current()));
    await assertFails(setDoc(backupRef(), backup()));
  });
  it('allows them once the marker exists and is live', async () => {
    await assertSucceeds(setDoc(markerRef(), marker()));
    await assertSucceeds(setDoc(designRef(), design()));
    await assertSucceeds(setDoc(currentRef(), current()));
    await assertSucceeds(setDoc(backupRef(), backup()));
  });
  it('refuses writes (creates and updates) while the marker says deleted', async () => {
    await seedMarker('alice', false);
    await assertSucceeds(setDoc(designRef(), design()));
    await assertSucceeds(setDoc(markerRef(), marker({ deleted: true })));
    await assertFails(setDoc(designRef(), design({ name: 'Renamed' })));
    await assertFails(setDoc(doc(alice(), `users/alice/designs/${'f'.repeat(32)}`), design()));
    await assertFails(setDoc(currentRef(), current()));
    await assertFails(setDoc(backupRef(), backup()));
  });
  it('still lets the owner read and delete everything while the marker says deleted (the wipe needs it)', async () => {
    await seedAlice();
    await env.withSecurityRulesDisabled((c) => setDoc(doc(c.firestore(), `users/alice/designs/${ID}`), { name: 'x', design: '{}', updatedAt: Timestamp.now(), deleted: false, schema: 1 }));
    await seedMarker('alice', true);
    await assertSucceeds(getDoc(designRef()));
    await assertSucceeds(getDocs(collection(alice(), 'users/alice/designs')));
    await assertSucceeds(getDoc(markerRef()));
    await assertSucceeds(deleteDoc(designRef()));
    await assertSucceeds(deleteDoc(currentRef()));
    await assertSucceeds(deleteDoc(backupRef()));
    await assertSucceeds(deleteDoc(markerRef()));
  });
  it('lets a kept account write again: the marker goes back to live', async () => {
    await seedMarker('alice', true);
    await assertFails(setDoc(designRef(), design()));
    await assertSucceeds(setDoc(markerRef(), marker({ deleted: false })));
    await assertSucceeds(setDoc(designRef(), design()));
  });
  it('refuses writes again after the marker is deleted (a wiped account)', async () => {
    await seedMarker('alice', false);
    await assertSucceeds(deleteDoc(markerRef()));
    await assertFails(setDoc(designRef(), design()));
  });
  it('lets a phone create the marker only live, and never as deleted', async () => {
    await assertFails(setDoc(markerRef(), marker({ deleted: true })));
    await assertSucceeds(setDoc(markerRef(), marker({ deleted: false })));
  });
  it('lets the owner mark a live account deleted and read it back', async () => {
    await seedMarker('alice', false);
    await assertSucceeds(setDoc(markerRef(), marker({ deleted: true })));
    const snap = await assertSucceeds(getDoc(markerRef()));
    assert.equal(snap.data().deleted, true);
  });
  it('checks the shape of the marker', async () => {
    await assertFails(setDoc(markerRef(), marker({ extra: 1 })));
    await assertFails(setDoc(markerRef(), marker({ deleted: 'no' })));
    await assertFails(setDoc(markerRef(), marker({ deleted: null })));
    await assertFails(setDoc(markerRef(), marker({ schema: '1' })));
    await assertFails(setDoc(markerRef(), marker({ schema: 0 })));
    await assertFails(setDoc(markerRef(), marker({ schema: 1001 })));
    await assertFails(setDoc(markerRef(), marker({ updatedAt: Timestamp.fromMillis(1) })));
    const { schema, ...missing } = marker();
    await assertFails(setDoc(markerRef(), missing));
    await assertFails(setDoc(doc(alice(), 'users/alice/meta/other'), marker()));
  });
  it('checks the shape of an update of the marker too', async () => {
    await seedMarker('alice', false);
    await assertFails(setDoc(markerRef(), marker({ deleted: true, extra: 1 })));
    await assertFails(setDoc(markerRef(), marker({ deleted: true, updatedAt: Timestamp.fromMillis(1) })));
  });
  it('lets another user nowhere near the marker, and the marker of another user never opens a gate', async () => {
    await seedMarker('alice', false);
    await assertFails(getDoc(doc(bob(), 'users/alice/meta/account')));
    await assertFails(setDoc(doc(bob(), 'users/alice/meta/account'), marker({ deleted: true })));
    await assertFails(deleteDoc(doc(bob(), 'users/alice/meta/account')));
    // bob has a live marker of his own: that does not let him write into alice's documents
    await seedMarker('bob', false);
    await assertFails(setDoc(doc(bob(), `users/alice/designs/${ID}`), design()));
    await assertSucceeds(setDoc(doc(bob(), `users/bob/designs/${ID}`), design()));
  });
  it('gates each user by their own marker', async () => {
    await seedMarker('alice', true);
    await seedMarker('bob', false);
    await assertFails(setDoc(designRef(), design()));
    await assertSucceeds(setDoc(doc(bob(), `users/bob/designs/${ID}`), design()));
  });
  it('denies unauthenticated access to the marker', async () => {
    await seedMarker('alice', false);
    await assertFails(getDoc(doc(anon(), 'users/alice/meta/account')));
    await assertFails(setDoc(doc(anon(), 'users/alice/meta/account'), marker()));
  });
});
