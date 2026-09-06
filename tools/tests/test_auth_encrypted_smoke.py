"""Deterministic framing/isolation regression tests; no server or crypto dependency."""
import pathlib
import socket
import time
import unittest
from unittest.mock import patch
import auth_encrypted_smoke as smoke


class FakeSocket:
    def __init__(self, chunks):
        self.chunks = iter(chunks)
        self.sent = []

    def recv(self, size):
        value = next(self.chunks, b'')
        if isinstance(value, Exception):
            raise value
        return value

    def sendall(self, value):
        self.sent.append(value)


class FramingTest(unittest.TestCase):
    def wire(self, chunks):
        wire = smoke.Wire.__new__(smoke.Wire)
        wire.socket = FakeSocket(chunks)
        wire.pending = bytearray()
        wire.encryptor = wire.decryptor = None
        wire.packets = 0
        return wire

    def test_varint_round_trip_boundaries(self):
        for value in (0, 1, 127, 128, 255, 16383, 16384, 2097152, 2147483647):
            encoded = smoke.varint(value)
            self.assertEqual(smoke.read_vi(encoded), (value, len(encoded)))

    def test_varint_rejects_negative_and_overflow(self):
        for value in (-1, 2147483648):
            with self.assertRaises(ValueError):
                smoke.varint(value)
        for value in (b'\x80' * 5, b'\xff' * 5, b'\xff\xff\xff\xff\x08'):
            with self.assertRaises(ValueError):
                smoke.read_vi(value)

    def test_incomplete_varint_is_not_a_complete_frame(self):
        with self.assertRaises(IndexError):
            smoke.read_vi(b'\x80')

    def test_utf16_counts_surrogate_pairs_in_code_units(self):
        self.assertEqual(smoke.secret('😀'), b'\x00\x02\xd8\x3d\xde\x00')
        self.assertEqual(smoke.secret(''), b'\x00\x00')
        self.assertEqual(len(smoke.secret('x' * 128)), 258)
        with self.assertRaises(ValueError):
            smoke.secret('😀' * 65)

    def test_blob_bounds_and_truncation(self):
        self.assertEqual(smoke.read_blob(smoke.text('abc'), 0), (b'abc', 4))
        for value in (b'\x03ab', smoke.varint(smoke.MAX_FRAME + 1)):
            with self.assertRaises(ValueError):
                smoke.read_blob(value, 0)

    def test_frame_survives_timeout_between_partial_varint_and_payload(self):
        body = smoke.varint(7) + b'x' * 200
        data = smoke.blob(body)
        wire = self.wire([data[:1], socket.timeout(), data[1:9], data[9:]])
        self.assertEqual(wire.receive(time.monotonic() + 1), (7, b'x' * 200))
        self.assertEqual(wire.pending, b'')

    def test_coalesced_frames_are_preserved(self):
        wire = self.wire([smoke.blob(b'\x01a') + smoke.blob(b'\x02b')])
        self.assertEqual(wire.receive(time.monotonic() + 1), (1, b'a'))
        self.assertEqual(wire.receive(time.monotonic() + 1), (2, b'b'))

    def test_zero_and_oversized_frames_rejected_before_allocation(self):
        for size in (0, smoke.MAX_FRAME + 1):
            wire = self.wire([smoke.varint(size)])
            with self.assertRaises(ValueError):
                wire.receive(time.monotonic() + 1)

    def test_absolute_deadline_and_eof_are_not_success(self):
        with self.assertRaises(TimeoutError):
            self.wire([]).receive(time.monotonic() - 1)
        with self.assertRaises(EOFError):
            self.wire([b'']).receive(time.monotonic() + 1)

    def test_packet_budget_is_bounded(self):
        wire = self.wire([smoke.blob(b'\x01')])
        wire.packets = 20000
        with self.assertRaises(ValueError):
            wire.receive(time.monotonic() + 1)

    def test_send_uses_packet_id_and_length(self):
        wire = self.wire([])
        wire.send(3, b'abc')
        self.assertEqual(wire.socket.sent, [b'\x04\x03abc'])

    def test_wire_only_connects_to_loopback(self):
        with patch.object(smoke.socket, 'create_connection') as connect:
            smoke.Wire(25569)
        connect.assert_called_once_with(('127.0.0.1', 25569), timeout=3)

    def test_default_invocation_never_modifies_runtime(self):
        source = pathlib.Path(smoke.__file__).read_text()
        self.assertIn("if os.geteuid() == 0 or not args.synthetic_fixture:", source)
        self.assertIn("'online-mode': 'true'", source)
        self.assertNotIn('allowOfflineDevelopment', source)
        self.assertIn("out.exists() or args.out.is_symlink()", source)
        self.assertIn("if any(initial[k]", source)
        self.assertIn("if out.exists()", source)


if __name__ == '__main__':
    unittest.main()
