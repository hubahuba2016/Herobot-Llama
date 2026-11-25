const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

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

test("Unauthenticated user cannot read or write any collection", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const past = new Date(Date.now() - 2000);

  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("messages").get());
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      displayName: "Unauth",
      createdAt: past,
      updatedAt: past,
    })
  );
  await assertFails(
    unauthDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("messages")
      .doc("msg_1")
      .set({
        userId: ALICE_UID,
        text: "Hello",
        senderType: "USER",
        clientTimestampMillis: 1700000000000,
        createdAt: past,
        updatedAt: past,
      })
  );
});

test("Authenticated owner: UserProfile create, partial update, and security guards", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const created = new Date(Date.now() - 10000);
  const userRef = aliceDb.collection("users").doc(ALICE_UID);

  await assertSucceeds(
    userRef.set({
      userId: ALICE_UID,
      displayName: "Alice",
      email: "alice@gmail.com",
      createdAt: created,
      updatedAt: created,
    })
  );

  await assertSucceeds(
    userRef.update({
      displayName: "Alice Updated",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Reject tampering createdAt
  await assertFails(
    userRef.update({
      createdAt: new Date(Date.now() - 500),
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Reject ghost field on create
  await assertFails(
    aliceDb.collection("users").doc("alice_other").set({
      userId: "alice_other",
      displayName: "Spoof",
      isAdmin: true,
      createdAt: created,
      updatedAt: created,
    })
  );

  // Reject future timestamp
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      displayName: "Alice Future",
      updatedAt: new Date(Date.now() + 60000),
    })
  );
});

test("Authenticated owner: Chat messages Kotlin payload, query, and deletion succeed", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const created = new Date(Date.now() - 10000);
  const messagesCol = aliceDb.collection("users").doc(ALICE_UID).collection("messages");

  // Empty subcollection list query (matches UserChatRepository.observeMessages)
  await assertSucceeds(messagesCol.orderBy("clientTimestampMillis", "asc").get());

  // User turn payload (no @DocumentId `id`, no null optional keys)
  await assertSucceeds(
    messagesCol.doc("msg_1").set({
      userId: ALICE_UID,
      text: "Explain gravity simply",
      senderType: "USER",
      clientTimestampMillis: 1700000001000,
      createdAt: created,
      updatedAt: created,
    })
  );

  // Bot turn payload with optional source/modelId/roleTitle
  await assertSucceeds(
    messagesCol.doc("msg_2").set({
      userId: ALICE_UID,
      text: "Gravity pulls mass toward mass.",
      senderType: "BOT",
      source: "GEMINI",
      modelId: "gemini-4-hydrogen",
      roleTitle: "General Reasoner",
      clientTimestampMillis: 1700000002000,
      createdAt: created,
      updatedAt: created,
    })
  );

  // Query after inserts
  await assertSucceeds(messagesCol.orderBy("clientTimestampMillis", "asc").get());

  // Reject extra `id` field
  await assertFails(
    messagesCol.doc("msg_3").set({
      id: "msg_3",
      userId: ALICE_UID,
      text: "Invalid id field",
      senderType: "USER",
      clientTimestampMillis: 1700000003000,
      createdAt: created,
      updatedAt: created,
    })
  );

  // Reject invalid source enum
  await assertFails(
    messagesCol.doc("msg_4").set({
      userId: ALICE_UID,
      text: "Invalid source",
      senderType: "BOT",
      source: "UNKNOWN_SOURCE",
      clientTimestampMillis: 1700000004000,
      createdAt: created,
      updatedAt: created,
    })
  );

  // Delete message succeeds for owner
  await assertSucceeds(messagesCol.doc("msg_1").delete());
});

test("Authenticated owner: Learned Q&A subcollection create, list, and cross-user isolation", async () => {
  const created = new Date(Date.now() - 10000);
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    await db.collection("users").doc(BOB_UID).set({
      userId: BOB_UID,
      displayName: "Bob",
      email: "bob@gmail.com",
      createdAt: created,
      updatedAt: created,
    });
    await db.collection("users").doc(BOB_UID).collection("messages").doc("bob_msg_1").set({
      userId: BOB_UID,
      text: "Bob private chat",
      senderType: "USER",
      clientTimestampMillis: 1700000000000,
      createdAt: created,
      updatedAt: created,
    });
    await db.collection("users").doc(BOB_UID).collection("learned_qa").doc("bob_qa_1").set({
      userId: BOB_UID,
      question: "bob secret",
      answer: "42",
      createdAt: created,
      updatedAt: created,
    });
  });

  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();

  // Alice can write & list her own learned_qa
  const aliceQaCol = aliceDb.collection("users").doc(ALICE_UID).collection("learned_qa");
  await assertSucceeds(
    aliceQaCol.doc("qa_1").set({
      userId: ALICE_UID,
      question: "What is my favorite color?",
      answer: "Teal",
      createdAt: created,
      updatedAt: created,
    })
  );
  await assertSucceeds(aliceQaCol.orderBy("createdAt", "asc").get());

  // Alice cannot read or write Bob's profile, messages, or learned_qa
  await assertFails(aliceDb.collection("users").doc(BOB_UID).get());
  await assertFails(aliceDb.collection("users").doc(BOB_UID).collection("messages").get());
  await assertFails(aliceDb.collection("users").doc(BOB_UID).collection("learned_qa").get());
  await assertFails(
    aliceDb
      .collection("users")
      .doc(BOB_UID)
      .collection("messages")
      .doc("hijack_msg")
      .set({
        userId: BOB_UID,
        text: "Hijack",
        senderType: "USER",
        clientTimestampMillis: 1700000005000,
        createdAt: created,
        updatedAt: created,
      })
  );
});
