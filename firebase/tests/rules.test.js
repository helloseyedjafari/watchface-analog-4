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
const backup = (over = {}) => ({
  sealed: 'AAAA', kdf: 'pbkdf2-hmac-sha256', iterations: 600000, salt: 'c2FsdHNhbHRzYWx0c2FsdA==',
  iv: 'aXZpdml2aXZpdml2', version: 1, createdAt: serverTimestamp(), keyFingerprint: '0123456789abcdef', ...over,
});

before(async () => {
  env = await initializeTestEnvironment({
    projectId: PROJECT,
    firestore: { rules: readFileSync(new URL('../firestore.rules', import.meta.url), 'utf8') },
  });
});
after(async () => { await env.cleanup(); });
beforeEach(async () => { await env.clearFirestore(); });

const alice = () => env.authenticatedContext('alice').firestore();
const bob = () => env.authenticatedContext('bob').firestore();
const anon = () => env.unauthenticatedContext().firestore();

describe('designs', () => {
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
  it('rejects a design id that is not 32 lowercase hex characters', async () => {
    await assertFails(setDoc(doc(alice(), 'users/alice/designs/not-an-id'), design()));
    await assertFails(setDoc(doc(alice(), `users/alice/designs/${ID.toUpperCase()}`), design()));
  });
});

describe('current design', () => {
  it('lets the owner write and read state/current only', async () => {
    const ref = doc(alice(), 'users/alice/state/current');
    await assertSucceeds(setDoc(ref, current()));
    await assertSucceeds(getDoc(ref));
    await assertFails(setDoc(doc(alice(), 'users/alice/state/other'), current()));
  });
  it('rejects bad fields and other users', async () => {
    await assertFails(setDoc(doc(alice(), 'users/alice/state/current'), current({ deviceId: '' })));
    await assertFails(setDoc(doc(alice(), 'users/alice/state/current'), current({ design: 'x'.repeat(950001) })));
    await assertFails(setDoc(doc(bob(), 'users/alice/state/current'), current()));
  });
});

describe('signing key backup', () => {
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
