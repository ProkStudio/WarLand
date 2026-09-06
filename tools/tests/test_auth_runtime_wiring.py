import json
import pathlib
import re
import unittest
ROOT=pathlib.Path(__file__).resolve().parents[2]
class AuthRuntimeWiring(unittest.TestCase):
 def source(self, name):
  return (ROOT / "src/main/java/ru/warland" / name).read_text()
 def test_fail_closed_mixins_are_registered(self):
  config=json.loads((ROOT/"src/main/resources/warland.mixins.json").read_text())
  self.assertTrue(config["required"]);self.assertEqual(config["injectors"]["defaultRequire"],1)
  for name in ["AuthConnectionMixin","AuthPlayMixin","AuthCommonMixin","AuthPlayerMixin","AuthPickupMixin","AuthModerationMixin"]:
   self.assertIn(name,config["mixins"])
 def test_lifecycle_and_profile_order(self):
  source=self.source("core/CoreRuntime.java")
  self.assertIn("store.start().thenCompose(v->auth.start())",source)
  self.assertLess(source.index("auth.close()"),source.index("store.close()"))
  join=source[source.index("private void onJoin"):source.index("private boolean clearArrival")]
  self.assertLess(join.index("auth.attach(p)"),join.index("store.tx("))
  self.assertLess(join.index("auth.authenticated(p)"),join.index("INSERT INTO profiles"))
  self.assertIn("auth.profileReady(p)",join)
  self.assertIn("config.requireOnlineModeForPublic",source)
 def test_both_command_paths_and_async_publications(self):
  source=self.source("mixin/AuthPlayMixin.java")
  for method in ["onCommandExecution","onChatCommandSigned","handleCommandExecution","executeCommand","addBook","updateBookContent","onSignUpdate","handleDecoratedMessage"]:
   self.assertIn('"'+method+'"',source)
  self.assertIn("RuntimePolicy.credentialCommand(packet.command())",source)
 def test_no_credential_commands_or_reset_endpoint(self):
  source=self.source("auth/AuthRuntime.java")
  self.assertIn("BEFORE_CONFIGURE",source);self.assertIn("connection.isEncrypted()",source)
  self.assertNotIn("changePassword(",source)
  self.assertNotRegex(source,r'literal\("(?:login|register|resetpassword)"')
  self.assertIn("s.identity.nonce().equals(request.nonce)",source)
  self.assertIn("StandardOpenOption.CREATE_NEW",source)
  self.assertIn("rw-------",source)
