const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_uid_001";
const BOB_UID = "bob_uid_002";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated users cannot read or write any collection", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const now = new Date(Date.now() - 1000);

  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("macros").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("scripts").get());
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("scripts").doc("s1").set({
      userId: ALICE_UID,
      title: "Unauth",
      description: "",
      code: "log('hi')",
      tags: [],
      lastRunStatus: "NEVER",
      createdAt: now,
      updatedAt: now,
    })
  );
});

test("User profile: owner create, partial update, shadow field & cross-user rejection", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const created = new Date(Date.now() - 5000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      displayName: "Alice Dev",
      email: "alice@example.com",
      themeMode: "SYSTEM",
      defaultTapDurationMs: 60,
      jitterRadiusPx: 4,
      createdAt: created,
      updatedAt: created,
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).update({
      themeMode: "DARK",
      jitterRadiusPx: 8,
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Reject ghost/shadow field
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      isAdmin: true,
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Reject immutable createdAt mutation
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      createdAt: new Date(Date.now() - 500),
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Reject future timestamp tampering
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      themeMode: "LIGHT",
      updatedAt: new Date(Date.now() + 60000),
    })
  );

  // Reject cross-user read and write
  await assertFails(bobDb.collection("users").doc(ALICE_UID).get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).update({ displayName: "Hacked" }));
});

test("Visual macros: Kotlin-mirrored create, update, list, delete & security bounds", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const created = new Date(Date.now() - 5000);

  // Empty subcollection list must succeed for owner
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("macros").get());

  // Create macro matching Kotlin VisualMacroRepository payload (NO 'id' field)
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("macros").doc("macro_01").set({
      userId: ALICE_UID,
      name: "Daily Claim Loop",
      description: "Clicks claim button and waits for green confirm",
      loopCount: 5,
      loopDelayMs: 1200,
      stopOnError: true,
      steps: [
        {
          stepId: "st_1",
          type: "CLICK",
          label: "Claim Button",
          x: 540,
          y: 1280,
          endX: 540,
          endY: 1280,
          durationMs: 60,
          delayAfterMs: 500,
          targetColorHex: "#2DD4BF",
          colorTolerance: 15,
          timeoutMs: 5000,
        },
      ],
      createdAt: created,
      updatedAt: created,
    })
  );

  // Partial update
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("macros").doc("macro_01").update({
      name: "Daily Claim Loop v2",
      loopCount: 10,
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Reject writing @DocumentId 'id' inside fields
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("macros").doc("macro_02").set({
      id: "macro_02",
      userId: ALICE_UID,
      name: "Bad ID",
      description: "",
      loopCount: 1,
      loopDelayMs: 0,
      stopOnError: false,
      steps: [],
      createdAt: created,
      updatedAt: created,
    })
  );

  // Cross-user isolation
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("macros").get());
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("macros").doc("macro_01").delete()
  );

  // Owner delete succeeds
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("macros").doc("macro_01").delete()
  );
});

test("Lua scripts: create, code update, status update & size/enum bounds", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const created = new Date(Date.now() - 5000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("scripts").doc("lua_01").set({
      userId: ALICE_UID,
      title: "Color Watcher",
      description: "Waits for teal pixel then taps",
      code: "if waitForColor(540, 1200, '#2DD4BF', 20, 5000) then click(540, 1200) end",
      tags: ["color", "demo"],
      lastRunStatus: "NEVER",
      createdAt: created,
      updatedAt: created,
    })
  );

  // Status-only update after execution
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("scripts").doc("lua_01").update({
      lastRunStatus: "SUCCESS",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Invalid enum rejected
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("scripts").doc("lua_01").update({
      lastRunStatus: "UNKNOWN_STATUS",
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Cross-user read rejected
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("scripts").doc("lua_01").get()
  );
});

test("Run history: append-only create & read by owner, update forbidden", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const created = new Date(Date.now() - 2000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("runHistory").doc("run_01").set({
      userId: ALICE_UID,
      targetType: "LUA",
      targetId: "lua_01",
      targetName: "Color Watcher",
      status: "SUCCESS",
      durationMs: 3200,
      actionsExecuted: 4,
      summaryMessage: "Completed cleanly",
      createdAt: created,
    })
  );

  // Updates are forbidden on immutable run history
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("runHistory").doc("run_01").update({
      status: "ERROR",
    })
  );

  // Owner list succeeds; Bob list fails
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("runHistory").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("runHistory").get());
});
