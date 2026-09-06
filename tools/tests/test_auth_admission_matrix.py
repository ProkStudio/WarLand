import time
import unittest
import auth_encrypted_smoke as smoke
import auth_admission_matrix as matrix


class Socket:
    def sendall(self, value):
        self.sent = value


class AdmissionMutationTest(unittest.TestCase):
    def wire(self, case='stale-nonce', packet=None, body=b''):
        wire = matrix.AttackWire.__new__(matrix.AttackWire)
        wire.socket = Socket()
        wire.pending = bytearray(smoke.blob(smoke.varint(packet) + body)) if packet is not None else bytearray()
        wire.encryptor = wire.decryptor = None
        wire.packets = 0
        wire.configuration = True
        wire.injected = False
        wire.case = case
        wire.result = {}
        return wire

    def request(self):
        return b'\x01' + b'\x11' * 16 + b'\x01' + smoke.secret('Synthetic-passphrase') * 2 + smoke.secret('')

    def send_attack(self, case):
        wire = self.wire(case)
        original = self.request()
        wire.send(2, smoke.text('warland:auth_request') + original)
        data = wire.socket.sent
        _, offset = smoke.read_vi(data)
        packet, offset = smoke.read_vi(data, offset)
        self.assertTrue(wire.result['attack_sent'])
        return wire, original, packet, data[offset:]

    def test_forged_ready_has_no_authentication_payload(self):
        _, _, packet, body = self.send_attack('forged-ready')
        self.assertEqual((packet, body), (3, b''))

    def test_stale_nonce_does_not_change_password_or_operation(self):
        _, original, packet, body = self.send_attack('stale-nonce')
        channel, offset = smoke.read_blob(body, 0)
        modified = body[offset:]
        self.assertEqual((packet, channel), (2, b'warland:auth_request'))
        self.assertNotEqual(modified[1:17], original[1:17])
        self.assertEqual(modified[17:], original[17:])

    def test_confirmation_changes_exactly_one_byte(self):
        _, original, _, body = self.send_attack('mismatched-confirmation')
        _, offset = smoke.read_blob(body, 0)
        modified = body[offset:]
        self.assertEqual(sum(a != b for a, b in zip(modified, original)), 1)
        self.assertEqual(modified[:18], original[:18])

    def test_finish_or_accepted_challenge_is_not_counted_as_denial(self):
        accepted = smoke.text('warland:auth_challenge') + b'\x11' * 16 + b'\x02'
        for packet, body in ((3, b''), (1, accepted)):
            wire = self.wire(packet=packet, body=body)
            with self.assertRaises(AssertionError):
                wire.receive(time.monotonic() + 1)

    def test_unrelated_disconnect_is_not_a_passing_test(self):
        for body in (b'Unknown failure', b'WarLand: test'):
            wire = self.wire(packet=2, body=body)
            wire.injected = True
            with self.assertRaises(AssertionError):
                wire.receive(time.monotonic() + 1)  # No actual encrypted transport marker.

    def test_warland_encrypted_denial_requires_an_attack(self):
        wire = self.wire(packet=2, body=b'WarLand: synthetic denial')
        wire.encryptor = object()
        with self.assertRaises(AssertionError):
            wire.receive(time.monotonic() + 1)

    def test_encrypted_warland_denial_after_attack_is_recognized(self):
        wire = self.wire(packet=2, body=b'WarLand: synthetic denial')
        wire.encryptor = object()
        wire.injected = True
        with self.assertRaises(matrix.ExpectedDenial):
            wire.receive(time.monotonic() + 1)
        self.assertTrue(wire.result['encrypted_warland_denial'])


if __name__ == '__main__':
    unittest.main()
