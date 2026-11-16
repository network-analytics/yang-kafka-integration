import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("consume_and_report.py")
SPEC = importlib.util.spec_from_file_location("consume_and_report", SCRIPT)
REPORTER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = REPORTER
SPEC.loader.exec_module(REPORTER)


class ConsumeAndReportTests(unittest.TestCase):
    def payload_line(self, subscription_id, xpath, sequence_number=None):
        payload = {
            "ietf-telemetry-message:message": {
                "telemetry-message-metadata": {
                    "ietf-yang-push-telemetry-message:yang-push-subscription": {
                        "id": subscription_id,
                        "xpath-filter": xpath,
                    }
                }
            }
        }
        if sequence_number is not None:
            payload["ietf-telemetry-message:message"]["payload"] = {
                "ietf-yp-notification:envelope": {
                    "hostname": "router-1",
                    "sequence-number": sequence_number,
                    "contents": {
                        "ietf-yang-push:push-update": {
                            "datastore-contents": {
                                "ietf-interfaces:interfaces": {
                                    "interface": [{"name": f"loopback-{sequence_number}"}]
                                }
                            }
                        }
                    },
                }
            }
        return REPORTER.DESERIALIZING_PREFIX + " " + json.dumps(payload)

    def test_parse_log_groups_success_failure_and_unconfirmed_records(self):
        lines = [
            self.payload_line(7, "/ietf-hardware:hardware/component"),
            "Message consumed: {}",
            self.payload_line(14, "/ietf-alarms:alarm-notification"),
            "YANG_DESERIALIZATION_FAILED topic=telemetry-yang schema-id=630 "
            "cause=YangCodecException: invalid payload",
            "errorTag='unknown-element', severity=Error, errorPath=/component",
            self.payload_line(17, "/ietf-interfaces:interfaces/interface"),
        ]

        outcomes, total = REPORTER.parse_log(lines)
        report = REPORTER.markdown_report(outcomes, total, Path("run.log"), 0)

        self.assertEqual(3, total)
        self.assertEqual((1, 0, 0), (outcomes["7"].passed, outcomes["7"].failed, outcomes["7"].unconfirmed))
        self.assertEqual((0, 1, 0), (outcomes["14"].passed, outcomes["14"].failed, outcomes["14"].unconfirmed))
        self.assertEqual((0, 0, 1), (outcomes["17"].passed, outcomes["17"].failed, outcomes["17"].unconfirmed))
        self.assertEqual({"630"}, outcomes["14"].schema_ids)
        self.assertIn("tag=unknown-element", report)
        self.assertIn("path=/component", report)

    def test_malformed_payload_is_grouped_as_unknown(self):
        outcomes, total = REPORTER.parse_log(
            [REPORTER.DESERIALIZING_PREFIX + " not-json", "Message consumed: {}"]
        )

        self.assertEqual(1, total)
        self.assertEqual(1, outcomes["unknown"].passed)
        self.assertIn("invalid JSON", outcomes["unknown"].xpath_filter)

    def test_java_payload_schema_id_is_preserved_in_reconciliation(self):
        payload_line = self.payload_line(101, "/ietf-interfaces:interfaces/interface", 10)
        payload = payload_line.split(": ", 1)[1]
        lines = [
            REPORTER.DESERIALIZING_PREFIX + " [schema-id=1589] " + payload,
            "Message consumed: " + payload,
            REPORTER.DESERIALIZING_PREFIX + " [schema-id=-1] " + payload,
            "YANG_DESERIALIZATION_FAILED topic=telemetry-yang schema-id=-1 cause=missing header",
        ]
        with tempfile.TemporaryDirectory() as directory:
            log_path = Path(directory) / "schema-id.log"
            log_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
            outcomes, total = REPORTER.parse_log(lines)
            REPORTER.reconcile_java_outcomes(log_path, outcomes)

        self.assertEqual(2, total)
        self.assertEqual({"1589"}, outcomes["101"].messages[0].schema_ids)
        self.assertEqual(set(), outcomes["101"].messages[1].schema_ids)
        self.assertEqual(["PASS", "FAIL"], [message.status for message in outcomes["101"].messages])
        self.assertEqual(json.loads(payload), json.loads(outcomes["101"].messages[0].payload_json))

    def test_java_slf4j_prefixed_markers_are_reconciled(self):
        successful_payload = self.payload_line(
            101, "/ietf-interfaces:interfaces/interface", sequence_number=10
        ).split(": ", 1)[1]
        failed_payload = self.payload_line(
            101, "/ietf-interfaces:interfaces/interface", sequence_number=11
        ).split(": ", 1)[1]
        log_prefix = "2026-11-16T10:00:00.000Z INFO AbstractKafkaYangJsonSchemaDeserializer - "
        lines = [
            log_prefix + REPORTER.DESERIALIZING_PREFIX + " [schema-id=1589] " + successful_payload,
            log_prefix + "Message consumed: " + successful_payload,
            log_prefix + REPORTER.DESERIALIZING_PREFIX + " [schema-id=1589] " + failed_payload,
            log_prefix
            + "YANG_DESERIALIZATION_FAILED topic=test schema-id=1589 "
            + "cause=validation failed",
            log_prefix + "errorTag='data-missing', errorPath=/interface/statistics",
        ]

        with tempfile.TemporaryDirectory() as directory:
            log_path = Path(directory) / "slf4j.log"
            log_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
            outcomes, total = REPORTER.parse_log(lines)
            shared_reasons = REPORTER.reconcile_java_outcomes(log_path, outcomes)

        self.assertEqual(2, total)
        self.assertEqual((1, 1, 0), (outcomes["101"].passed, outcomes["101"].failed, outcomes["101"].unconfirmed))
        self.assertEqual({"1589"}, outcomes["101"].schema_ids)
        self.assertEqual(["PASS", "FAIL"], [message.status for message in outcomes["101"].messages])
        self.assertIn("path=/interface/statistics", shared_reasons.most_common()[0][0])

    def test_netgauze_log_format_groups_pass_and_failure(self):
        payload = {
            "ietf-telemetry-message:message": {
                "telemetry-message-metadata": {
                    "ietf-yang-push-telemetry-message:yang-push-subscription": {
                        "id": 19,
                        "xpath-filter": "/ietf-alarms:alarm-notification",
                    }
                }
            }
        }
        payload_text = json.dumps(payload)
        lines = [
            f"\x1b[34mDEBUG\x1b[0m netcalyx: Message Payload [partition: 0, offset: 1, key: \"bns\", schema_id: 1236]: {payload_text}",
            "Message validation PASSED [partition: 0, offset: 1, schema_id: 1236]",
            f"Message Payload [partition: 0, offset: 2, key: \"bns\", schema_id: 1236]: {payload_text}",
            "Message validation FAILED [partition: 0, offset: 2, schema_id: 1236]: errorTag='unknown-element', errorPath=/alarm-notification",
        ]

        outcomes, total = REPORTER.parse_log(lines)
        report = REPORTER.markdown_report(outcomes, total, Path("bns.log"), 0)

        self.assertEqual(2, total)
        self.assertEqual(1, outcomes["19"].passed)
        self.assertEqual(1, outcomes["19"].failed)
        self.assertEqual({"1236"}, outcomes["19"].schema_ids)
        self.assertIn("Message validation FAILED", report)
        self.assertIn("path=/alarm-notification", report)
        self.assertIn("## Payload Results", report)
        self.assertIn("Payload and Failure Evidence", report)
        self.assertNotIn("| Failure detail |", report)
        self.assertIn("| 19 | PASS |", report)
        self.assertIn("| 19 | FAIL |", report)
        self.assertIn(
            "Result evidence:</strong> Failure reason: Message validation FAILED",
            report,
        )
        self.assertIn("tag=unknown-element; path=/alarm-notification", report)
        self.assertIn("alarm-notification", report)

    def test_payload_results_include_full_logged_json_payload(self):
        payload = {
            "ietf-telemetry-message:message": {
                "telemetry-message-metadata": {
                    "ietf-yang-push-telemetry-message:yang-push-subscription": {
                        "id": 19,
                        "xpath-filter": "/ietf-alarms:alarm-notification",
                    }
                },
                "payload": {
                    "ietf-yp-notification:envelope": {
                        "contents": {
                            "ietf-yang-push:push-update": {
                                "datastore-contents": {"large-value": "x" * 500}
                            }
                        }
                    }
                },
            }
        }
        payload_text = json.dumps(payload)
        lines = [
            f"Message Payload [partition: 0, offset: 1, schema_id: 1236]: {payload_text}",
            "Message validation PASSED [partition: 0, offset: 1, schema_id: 1236]",
        ]

        outcomes, total = REPORTER.parse_log(lines)
        report = REPORTER.markdown_report(outcomes, total, Path("full-payload.log"), 0)

        result = outcomes["19"].messages[0]
        self.assertIn("telemetry-message-metadata", result.payload_json)
        self.assertIn("large-value", result.payload_json)
        self.assertEqual("x" * 500, json.loads(result.payload_json)["ietf-telemetry-message:message"]["payload"]["ietf-yp-notification:envelope"]["contents"]["ietf-yang-push:push-update"]["datastore-contents"]["large-value"])
        self.assertIn("telemetry-message-metadata", report)
        self.assertNotIn("...", result.payload_json)

    def test_java_reconciliation_reports_each_payload_by_sequence(self):
        successful_10 = self.payload_line(
            101, "/ietf-interfaces:interfaces/interface", sequence_number=10
        )
        successful_11 = self.payload_line(
            101, "/ietf-interfaces:interfaces/interface", sequence_number=11
        )
        lines = [
            successful_10,
            successful_11,
            "YANG_DESERIALIZATION_FAILED topic=telemetry-yang schema-id=1589 "
            "cause=YangCodecException: validation failed",
            "errorTag='data-missing', severity=Error, errorPath=/interface/statistics",
            "Message consumed: " + successful_11.split(": ", 1)[1],
        ]

        with tempfile.TemporaryDirectory() as directory:
            log_path = Path(directory) / "sequence-matched.log"
            log_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
            outcomes, total = REPORTER.parse_log(lines)
            shared_reasons = REPORTER.reconcile_java_outcomes(log_path, outcomes)
            report = REPORTER.markdown_report(outcomes, total, log_path, 0, shared_reasons)

        messages = outcomes["101"].messages
        self.assertEqual(["FAIL", "PASS"], [message.status for message in messages])
        self.assertIn('"sequence-number":10', messages[0].payload_json)
        self.assertIn('"sequence-number":11', messages[1].payload_json)
        self.assertIn("| 101 | FAIL |", report)
        self.assertIn("| 101 | PASS |", report)
        self.assertIn("Replay-level diagnostics (not attributed to this payload)", report)
        self.assertIn("path=/interface/statistics", report)

    def test_truncated_netgauze_payload_keeps_subscription_and_filter(self):
        xpath = "/ietf-hardware:hardware/component/sensor-data"
        control = json.loads(self.payload_line(103, xpath).split(": ", 1)[1])
        truncated_payload = (
            '{"ietf-telemetry-message:message":{"payload":'
            '{"ietf-yp-notification:envelope":{"contents":'
            '{"ietf-yang-push:push-update":{"datastore-contents":{},'
            '"id":103,"ietf-distributed-notif:message-publisher-id":0'
        )
        lines = [
            "Message Payload [partition: 0, offset: 1, key: \"bns\", schema_id: 118694]: "
            + json.dumps(control),
            "Message validation PASSED [partition: 0, offset: 1, schema_id: 118694]",
            "Message Payload [partition: 0, offset: 2, key: \"bns\", schema_id: 118694]: "
            + truncated_payload,
        ]

        outcomes, total = REPORTER.parse_log(lines)
        report = REPORTER.markdown_report(outcomes, total, Path("truncated.log"), 0)

        self.assertEqual(2, total)
        self.assertNotIn("unknown", outcomes)
        self.assertEqual(xpath, outcomes["103"].xpath_filter)
        self.assertEqual(["PASS", "UNCONFIRMED"], [message.status for message in outcomes["103"].messages])
        self.assertEqual({"118694"}, outcomes["103"].messages[1].schema_ids)
        self.assertIn("| 103 | UNCONFIRMED |", report)

    def test_java_outcomes_reconcile_when_output_streams_interleave(self):
        successful_103 = self.payload_line(103, "/ietf-hardware:hardware/component")
        successful_104 = self.payload_line(104, "/ietf-alarms:alarms/alarm-list")
        lines = [
            self.payload_line(101, "/ietf-interfaces:interfaces/interface"),
            self.payload_line(103, "/ietf-hardware:hardware/component"),
            "YANG_DESERIALIZATION_FAILED topic=telemetry-yang schema-id=1589 "
            "cause=YangCodecException: validation failed",
            "errorTag='data-missing', severity=Error, errorPath=/interface/statistics",
            self.payload_line(104, "/ietf-alarms:alarms/alarm-list"),
            "Message consumed: " + successful_103.split(": ", 1)[1],
            "Message consumed: " + successful_104.split(": ", 1)[1],
        ]

        with tempfile.TemporaryDirectory() as directory:
            log_path = Path(directory) / "interleaved.log"
            log_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
            outcomes, total = REPORTER.parse_log(lines)
            shared_reasons = REPORTER.reconcile_java_outcomes(log_path, outcomes)
            report = REPORTER.markdown_report(outcomes, total, log_path, 0, shared_reasons)

        self.assertEqual(3, total)
        self.assertEqual((0, 1, 0), (outcomes["101"].passed, outcomes["101"].failed, outcomes["101"].unconfirmed))
        self.assertEqual((1, 0, 0), (outcomes["103"].passed, outcomes["103"].failed, outcomes["103"].unconfirmed))
        self.assertEqual((1, 0, 0), (outcomes["104"].passed, outcomes["104"].failed, outcomes["104"].unconfirmed))
        self.assertIn("Shared Failure Diagnostics", report)
        self.assertIn("path=/interface/statistics", report)

    def test_run_consumer_captures_output_in_quiet_mode(self):
        with tempfile.TemporaryDirectory() as directory:
            log_path = Path(directory) / "consumer.log"
            exit_code = REPORTER.run_consumer(
                [sys.executable, "-c", "print('consumer output')"], log_path, quiet=True
            )

            self.assertEqual(0, exit_code)
            self.assertEqual("consumer output\n", log_path.read_text(encoding="utf-8"))

    def test_input_log_mode_writes_report_without_running_consumer(self):
        with tempfile.TemporaryDirectory() as directory:
            log_path = Path(directory) / "bns.log"
            report_path = Path(directory) / "bns-report.md"
            log_path.write_text(
                self.payload_line(17, "/ieee802-dot1ab-lldp:lldp/port")
                + "\nMessage validation PASSED [schema_id: 1236]\n",
                encoding="utf-8",
            )

            exit_code = REPORTER.main(
                ["--input-log", str(log_path), "--report", str(report_path)]
            )

            self.assertEqual(0, exit_code)
            report = report_path.read_text(encoding="utf-8")
            self.assertIn("Payloads: **1**; passed: **1**; failed: **0**", report)
            self.assertIn("| 17 |", report)


if __name__ == "__main__":
    unittest.main()