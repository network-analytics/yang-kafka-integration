#!/usr/bin/env python3
"""Run a consumer command and write a per-subscription Markdown report.

The parser supports both the Java/AWE markers (``Deserializing payload:`` and
``YANG_DESERIALIZATION_FAILED``) and NetGauze/BNS markers (``Message Payload
[... schema_id: N]:`` and ``Message validation PASSED/FAILED``). The combined
stdout/stderr log is kept so each outcome can be associated with its payload.

Example:
    python3 scripts/consume_and_report.py \
      --log /bp2/data/alive-run.log \
      --report /bp2/data/alive-run-report.md \
      -- java -cp '/bp2/data/*:/bp2/src/lib/*' Solution.java

Press Ctrl-C to stop the consumer; the report is still written afterward.
To report on a BNS log produced separately, use:

        python3 scripts/consume_and_report.py \
            --input-log /path/to/bns-consumer.log \
            --report /path/to/bns-report.md
"""

from __future__ import annotations

import argparse
import html
import json
import os
import re
import signal
import subprocess
import sys
from collections import Counter
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Iterable


DESERIALIZING_PREFIX = "Deserializing payload:"
FAILURE_PREFIX = "YANG_DESERIALIZATION_FAILED"
NETGAUZE_PAYLOAD_PREFIX = "Message Payload ["
FAILURE_MARKERS = (FAILURE_PREFIX, "Message validation FAILED")
SUCCESS_MARKERS = ("Message consumed:", "YANG document:", "Message validation PASSED")
ANSI_ESCAPE_PATTERN = re.compile(r"\x1b\[[0-?]*[ -/]*[@-~]")
SCHEMA_ID_PATTERN = re.compile(r"\b(?:schema-id|schema_id)\s*[:=]\s*(\d+)")
JAVA_SCHEMA_ID_PREFIX = re.compile(r"^\[schema-id=(-?\d+)\]\s*")
CAUSE_PATTERN = re.compile(r"\bcause=(.*)")
ERROR_TAG_PATTERN = re.compile(r"errorTag='([^']+)'")
ERROR_PATH_PATTERN = re.compile(r"errorPath=([^,\r\n]+)")
ERROR_MESSAGE_PATTERN = re.compile(r"errorMessage=([^,\r\n]+)")
ERROR_DETAIL_PREFIXES = ("errorTag=", "errorPath=", "errorMessage=")
PARTIAL_SUBSCRIPTION_ID_PATTERN = re.compile(
    r'"id"\s*:\s*(\d+)\s*,\s*"ietf-distributed-notif:message-publisher-id"'
)


@dataclass
class PayloadOutcome:
    subscription_id: str
    xpath_filter: str
    schema_ids: set[str] = field(default_factory=set)
    passed: int = 0
    failed: int = 0
    unconfirmed: int = 0
    reasons: Counter[str] = field(default_factory=Counter)
    messages: list["MessageResult"] = field(default_factory=list)


@dataclass
class MessageResult:
    subscription_id: str
    xpath_filter: str
    status: str
    payload_json: str
    schema_ids: set[str] = field(default_factory=set)
    failure_reason: str = ""


@dataclass
class PendingRecord:
    subscription_id: str
    xpath_filter: str
    schema_id: str = ""
    payload_text: str = ""
    failure_details: list[str] = field(default_factory=list)
    failure: bool = False
    success: bool = False


def parse_payload_line(line: str) -> PendingRecord | None:
    line = ANSI_ESCAPE_PATTERN.sub("", line).strip()
    payload_marker = line.find(DESERIALIZING_PREFIX)
    if payload_marker >= 0:
        payload_text = line[payload_marker + len(DESERIALIZING_PREFIX) :].strip()
        schema_id_match = JAVA_SCHEMA_ID_PREFIX.match(payload_text)
        schema_id = ""
        if schema_id_match:
            schema_id = schema_id_match.group(1)
            if schema_id == "-1":
                schema_id = ""
            payload_text = payload_text[schema_id_match.end() :]
        subscription_id, xpath_filter = parse_payload_metadata(payload_text)
        return PendingRecord(subscription_id, xpath_filter, schema_id=schema_id, payload_text=payload_text)

    payload_marker = line.find(NETGAUZE_PAYLOAD_PREFIX)
    if payload_marker < 0:
        return None
    header_start = payload_marker + len(NETGAUZE_PAYLOAD_PREFIX)
    header_end = line.find("]:", header_start)
    if header_end < 0:
        return None
    header = line[header_start:header_end]
    payload_text = line[header_end + 2 :].strip()
    subscription_id, xpath_filter = parse_payload_metadata(payload_text)
    record = PendingRecord(subscription_id, xpath_filter, payload_text=payload_text)
    schema_id_match = SCHEMA_ID_PATTERN.search(header)
    if schema_id_match:
        record.schema_id = schema_id_match.group(1)
    return record


def parse_payload_metadata(payload_text: str) -> tuple[str, str]:
    try:
        document = json.loads(payload_text)
    except (json.JSONDecodeError, TypeError):
        partial_id_match = PARTIAL_SUBSCRIPTION_ID_PATTERN.search(payload_text)
        if partial_id_match:
            return partial_id_match.group(1), "not provided"
        return "unknown", "not available (invalid JSON)"
    if not isinstance(document, dict):
        return "unknown", "not available (invalid JSON structure)"
    message = document.get("ietf-telemetry-message:message", {})
    metadata = message.get("telemetry-message-metadata", {}) if isinstance(message, dict) else {}
    subscription = (
        metadata.get("ietf-yang-push-telemetry-message:yang-push-subscription", {})
        if isinstance(metadata, dict)
        else {}
    )
    if not isinstance(subscription, dict):
        return "unknown", "not available (invalid subscription metadata)"
    subscription_id = str(subscription.get("id", "unknown"))
    xpath_filter = subscription.get("xpath-filter") or subscription.get("subtree-filter")
    return subscription_id, str(xpath_filter or "not provided")


def payload_identity(record: PendingRecord) -> tuple[str, ...]:
    try:
        document = json.loads(record.payload_text)
    except (json.JSONDecodeError, TypeError):
        return record.subscription_id, "text", record.payload_text

    message = document.get("ietf-telemetry-message:message", {})
    payload = message.get("payload", {}) if isinstance(message, dict) else {}
    envelope = payload.get("ietf-yp-notification:envelope", {}) if isinstance(payload, dict) else {}
    if not envelope and isinstance(document.get("ietf-yp-notification:envelope"), dict):
        envelope = document["ietf-yp-notification:envelope"]
    if isinstance(envelope, dict) and envelope.get("sequence-number") is not None:
        contents = envelope.get("contents", {})
        event_name = next(iter(contents), "") if isinstance(contents, dict) else ""
        return (
            record.subscription_id,
            str(envelope.get("hostname", "")),
            str(envelope["sequence-number"]),
            event_name,
        )
    return record.subscription_id, "json", json.dumps(document, sort_keys=True, separators=(",", ":"))


def full_payload_json(payload_text: str) -> str:
    try:
        document = json.loads(payload_text)
    except (json.JSONDecodeError, TypeError):
        return payload_text
    return json.dumps(document, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def failure_reason(lines: Iterable[str]) -> str:
    detail_lines = [line.strip() for line in lines if line.strip()]
    text = " ".join(detail_lines)
    cause_match = CAUSE_PATTERN.search(detail_lines[0]) if detail_lines else None
    cause = cause_match.group(1).strip() if cause_match else (detail_lines[0] if detail_lines else "")
    if FAILURE_PREFIX not in cause and "Message validation FAILED" in cause:
        cause = cause[cause.index("Message validation FAILED") :]
    for marker in (" errorTag=", " errorPath=", " errorMessage="):
        if marker in cause:
            cause = cause.split(marker, 1)[0].strip()

    tag_match = ERROR_TAG_PATTERN.search(text)
    path_match = ERROR_PATH_PATTERN.search(text)
    message_match = ERROR_MESSAGE_PATTERN.search(text)
    details = []
    if tag_match:
        details.append("tag=" + tag_match.group(1))
    if path_match:
        details.append("path=" + path_match.group(1).strip())
    if message_match:
        details.append("detail=" + message_match.group(1).strip())
    reason = cause
    if details:
        reason += " [" + "; ".join(details) + "]"
    reason = reason.replace("|", "\\|").replace("\r", " ").replace("\n", " ")
    if len(reason) > 400:
        reason = reason[:397] + "..."
    return reason or "Deserialization failed without a reported reason"


def parse_log(lines: Iterable[str]) -> tuple[dict[str, PayloadOutcome], int]:
    outcomes: dict[str, PayloadOutcome] = {}
    total = 0
    pending: PendingRecord | None = None
    subscription_filters: dict[str, str] = {}

    def finish(record: PendingRecord) -> None:
        nonlocal total
        total += 1
        outcome = outcomes.setdefault(
            record.subscription_id,
            PayloadOutcome(record.subscription_id, record.xpath_filter),
        )
        if record.xpath_filter != "not provided" and record.xpath_filter != outcome.xpath_filter:
            outcome.xpath_filter = ", ".join(sorted({outcome.xpath_filter, record.xpath_filter}))
        if record.schema_id:
            outcome.schema_ids.add(record.schema_id)
        if record.failure:
            outcome.failed += 1
            reason = failure_reason(record.failure_details)
            outcome.reasons[reason] += 1
            status = "FAIL"
        elif record.success:
            outcome.passed += 1
            reason = ""
            status = "PASS"
        else:
            outcome.unconfirmed += 1
            reason = ""
            status = "UNCONFIRMED"
        outcome.messages.append(
            MessageResult(
                record.subscription_id,
                record.xpath_filter,
                status,
                full_payload_json(record.payload_text),
                {record.schema_id} if record.schema_id else set(),
                reason,
            )
        )

    for line in lines:
        line = ANSI_ESCAPE_PATTERN.sub("", line).strip()
        record = parse_payload_line(line)
        if record is not None:
            if pending is not None:
                finish(pending)
            if record.xpath_filter not in ("not provided",) and not record.xpath_filter.startswith(
                "not available"
            ):
                subscription_filters[record.subscription_id] = record.xpath_filter
            elif record.subscription_id in subscription_filters:
                record.xpath_filter = subscription_filters[record.subscription_id]
            pending = record
            continue

        if pending is None:
            continue

        if any(marker in line for marker in FAILURE_MARKERS):
            pending.failure = True
            pending.failure_details.append(line)
            schema_id_match = SCHEMA_ID_PATTERN.search(line)
            if schema_id_match and not pending.schema_id:
                pending.schema_id = schema_id_match.group(1)
            continue

        if pending.failure and (
            any(prefix in line for prefix in ERROR_DETAIL_PREFIXES)
            or any(
                marker in line.lower()
                for marker in ("validation error", "invalid value", "unknown element", "leafref")
            )
        ):
            pending.failure_details.append(line)
            continue

        if not pending.failure and any(marker in line for marker in SUCCESS_MARKERS):
            pending.success = True
            finish(pending)
            pending = None

    if pending is not None:
        finish(pending)
    return outcomes, total


def reconcile_java_outcomes(
    log_path: Path, outcomes: dict[str, PayloadOutcome]
) -> Counter[str] | None:
    attempts: Counter[str] = Counter()
    successes: Counter[str] = Counter()
    failure_reasons: Counter[str] = Counter()
    schema_ids: set[str] = set()
    failure_details: list[str] = []
    failure_count = 0
    found_java_payload = False
    attempts_by_subscription: dict[str, list[PendingRecord]] = {}
    success_records: list[PendingRecord] = []

    def finish_failure() -> None:
        if failure_details:
            failure_reasons[failure_reason(failure_details)] += 1
            failure_details.clear()

    with log_path.open("r", encoding="utf-8", errors="replace") as log_file:
        for raw_line in log_file:
            line = ANSI_ESCAPE_PATTERN.sub("", raw_line).strip()
            payload_marker = line.find(DESERIALIZING_PREFIX)
            if payload_marker >= 0:
                finish_failure()
                found_java_payload = True
                record = parse_payload_line(line)
                assert record is not None
                attempts[record.subscription_id] += 1
                attempts_by_subscription.setdefault(record.subscription_id, []).append(record)
                continue

            message_marker = line.find("Message consumed:")
            if message_marker >= 0:
                finish_failure()
                payload_text = line[message_marker + len("Message consumed:") :].strip()
                subscription_id, xpath_filter = parse_payload_metadata(payload_text)
                success_records.append(
                    PendingRecord(subscription_id, xpath_filter, payload_text=payload_text, success=True)
                )
                successes[subscription_id] += 1
                continue

            if FAILURE_PREFIX in line:
                finish_failure()
                failure_count += 1
                failure_details.append(line)
                schema_match = SCHEMA_ID_PATTERN.search(line)
                if schema_match:
                    schema_ids.add(schema_match.group(1))
                continue

            if failure_details and (
                any(prefix in line for prefix in ERROR_DETAIL_PREFIXES)
                or any(
                    marker in line.lower()
                    for marker in ("validation error", "invalid value", "unknown element", "leafref")
                )
            ):
                failure_details.append(line)

    finish_failure()
    if not found_java_payload:
        return None

    expected_failures = sum(
        max(payload_count - successes.get(subscription_id, 0), 0)
        for subscription_id, payload_count in attempts.items()
    )
    if expected_failures != failure_count or sum(attempts.values()) != sum(
        outcome.passed + outcome.failed + outcome.unconfirmed for outcome in outcomes.values()
    ):
        return None

    unmatched: dict[tuple[str, ...], list[int]] = {}
    for subscription_id, records in attempts_by_subscription.items():
        for index, record in enumerate(records):
            unmatched.setdefault(payload_identity(record), []).append(index)
    matched: dict[str, set[int]] = {subscription_id: set() for subscription_id in attempts_by_subscription}
    for success in success_records:
        candidates = unmatched.get(payload_identity(success), [])
        index = next(
            (candidate for candidate in candidates if candidate not in matched.get(success.subscription_id, set())),
            None,
        )
        if index is None:
            remaining = [
                candidate
                for candidate in range(len(attempts_by_subscription.get(success.subscription_id, [])))
                if candidate not in matched.get(success.subscription_id, set())
            ]
            if len(remaining) == 1:
                index = remaining[0]
        if index is not None and success.subscription_id in matched:
            matched[success.subscription_id].add(index)

    can_classify_failures = (
        expected_failures == failure_count
        and sum(len(indices) for indices in matched.values()) == sum(successes.values())
    )

    for subscription_id, payload_count in attempts.items():
        outcome = outcomes[subscription_id]
        outcome.passed = min(successes.get(subscription_id, 0), payload_count)
        outcome.failed = payload_count - outcome.passed
        outcome.unconfirmed = 0
        outcome.reasons.clear()
        outcome.messages.clear()
        if outcome.failed:
            outcome.reasons[
                "Java failure markers do not contain a subscription ID; see shared failure diagnostics"
            ] = outcome.failed
        for index, record in enumerate(attempts_by_subscription.get(subscription_id, [])):
            passed = index in matched.get(subscription_id, set())
            status = "PASS" if passed else "FAIL" if can_classify_failures else "UNCONFIRMED"
            reason = (
                "Deserialization failed; see Shared Failure Diagnostics."
                if status == "FAIL"
                else ""
            )
            outcome.messages.append(
                MessageResult(
                    record.subscription_id,
                    record.xpath_filter,
                    status,
                    full_payload_json(record.payload_text),
                    {record.schema_id} if record.schema_id else set(),
                    reason,
                )
            )
    if outcomes and not any(record.schema_id for records in attempts_by_subscription.values() for record in records):
        next(iter(outcomes.values())).schema_ids.update(schema_ids)
    return failure_reasons


def markdown_report(
    outcomes: dict[str, PayloadOutcome],
    total: int,
    log_path: Path,
    exit_code: int | None,
    shared_failure_reasons: Counter[str] | None = None,
) -> str:
    passed = sum(outcome.passed for outcome in outcomes.values())
    failed = sum(outcome.failed for outcome in outcomes.values())
    unconfirmed = sum(outcome.unconfirmed for outcome in outcomes.values())
    schema_ids = sorted({schema_id for outcome in outcomes.values() for schema_id in outcome.schema_ids})

    lines = [
        "# Kafka YANG Consumer Report",
        "",
        f"- Generated: `{datetime.now().astimezone().isoformat(timespec='seconds')}`",
        f"- Log: `{log_path}`",
        f"- Consumer exit code: `{exit_code if exit_code is not None else 'running'}`",
        f"- Payloads: **{total}**; passed: **{passed}**; failed: **{failed}**; unconfirmed: **{unconfirmed}**",
        f"- Schema IDs observed in payloads or failure diagnostics: {', '.join(schema_ids) if schema_ids else 'none'}",
        "",
        "| Subscription | XPath filter | Total | Passed | Failed | Unconfirmed | Failure reasons |",
        "|---:|---|---:|---:|---:|---:|---|",
    ]
    for subscription_id in sorted(outcomes, key=subscription_sort_key):
        outcome = outcomes[subscription_id]
        reasons = "; ".join(
            f"{reason} (x{count})"
            for reason, count in outcome.reasons.most_common()
        ) or "None"
        lines.append(
            f"| {subscription_id} | `{outcome.xpath_filter}` | "
            f"{outcome.passed + outcome.failed + outcome.unconfirmed} | {outcome.passed} | "
            f"{outcome.failed} | {outcome.unconfirmed} | {reasons} |"
        )
    messages = [message for outcome in outcomes.values() for message in outcome.messages]
    if messages:
        lines.extend(
            [
                "",
                "## Payload Results",
                "Each row is one payload attempt. Java failures are linked to unmatched attempts only when payload identity and replay counts reconcile; the Java error marker itself does not include a subscription ID.",
                "",
                "| Subscription | Result | XPath filter | Schema ID | Payload and Failure Evidence |",
                "|---:|---|---|---|---|",
            ]
        )
        for message in messages:
            schema_ids = ", ".join(sorted(message.schema_ids)) or "not reported per message"
            evidence = message.failure_reason
            if message.status == "FAIL":
                if evidence == "Deserialization failed; see Shared Failure Diagnostics.":
                    diagnostics = "; ".join(
                        f"{reason} (x{count})"
                        for reason, count in (shared_failure_reasons or Counter()).most_common()
                    )
                    evidence = (
                        "Replay-level diagnostics (not attributed to this payload): "
                        + diagnostics
                        if diagnostics
                        else "Java failure marker recorded; no detailed diagnostic was captured."
                    )
                else:
                    evidence = "Failure reason: " + (evidence or "No error detail was captured.")
            elif message.status == "PASS":
                evidence = "PASS evidence: matching consumer success marker."
            else:
                evidence = "UNCONFIRMED: no matching success or failure marker was captured."
            payload_cell = (
                "<strong>Full payload JSON:</strong><pre><code>"
                + escape_html_cell(message.payload_json)
                + "</code></pre><strong>Result evidence:</strong> "
                + escape_html_cell(evidence)
            )
            lines.append(
                f"| {message.subscription_id} | {message.status} | `{escape_markdown(message.xpath_filter)}` | "
                f"{schema_ids} | {payload_cell} |"
            )
    if shared_failure_reasons:
        lines.extend(
            [
                "",
                "## Shared Failure Diagnostics",
                "The Java deserializer failure markers do not include subscription IDs, so these reasons are shown at replay level.",
            ]
        )
        for reason, count in shared_failure_reasons.most_common():
            lines.append(f"- {escape_markdown(reason)} (x{count})")
    lines.append("")
    return "\n".join(lines)


def escape_markdown(value: str) -> str:
    return value.replace("|", "\\|").replace("\r", " ").replace("\n", " ")


def escape_html_cell(value: str) -> str:
    return html.escape(value, quote=True).replace("|", "&#124;").replace("\r", " ").replace("\n", " ")


def subscription_sort_key(value: str) -> tuple[int, str]:
    try:
        return int(value), ""
    except ValueError:
        return sys.maxsize, value


def run_consumer(command: list[str], log_path: Path, quiet: bool = False) -> int:
    log_path.parent.mkdir(parents=True, exist_ok=True)
    process = subprocess.Popen(
        command,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
        bufsize=1,
        start_new_session=True,
    )

    def forward_signal(signum: int, _frame: object) -> None:
        if process.poll() is None:
            try:
                os.killpg(process.pid, signum)
            except ProcessLookupError:
                pass

    old_sigint = signal.signal(signal.SIGINT, forward_signal)
    old_sigterm = signal.signal(signal.SIGTERM, forward_signal)
    try:
        assert process.stdout is not None
        with log_path.open("x", encoding="utf-8", errors="replace") as log_file:
            for line in process.stdout:
                if not quiet:
                    sys.stdout.write(line)
                    sys.stdout.flush()
                log_file.write(line)
        return process.wait()
    finally:
        if process.stdout is not None:
            process.stdout.close()
        signal.signal(signal.SIGINT, old_sigint)
        signal.signal(signal.SIGTERM, old_sigterm)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--log", type=Path, help="Combined consumer output log path")
    parser.add_argument("--report", type=Path, help="Markdown report path")
    parser.add_argument("--input-log", type=Path, help="Summarize an existing combined log")
    parser.add_argument(
        "--quiet", action="store_true", help="Do not echo consumer output to the terminal"
    )
    parser.add_argument("command", nargs=argparse.REMAINDER, help="Consumer command after --")
    args = parser.parse_args(argv)

    command = args.command
    if command and command[0] == "--":
        command = command[1:]
    if args.input_log is not None and command:
        parser.error("--input-log cannot be combined with a consumer command")
    if args.input_log is not None and args.log is not None:
        parser.error("--input-log cannot be combined with --log")
    if args.input_log is None and not command:
        parser.error("provide a consumer command after --, or use --input-log")

    timestamp = datetime.now().astimezone().strftime("%Y%m%d-%H%M%S")
    log_path = args.input_log or args.log or Path(f"consumer-run-{timestamp}.log")
    report_path = args.report or log_path.with_suffix(".md")
    if args.input_log is None and log_path.exists():
        parser.error(f"log already exists; choose a new path: {log_path}")
    if report_path.exists():
        parser.error(f"report already exists; choose a new path: {report_path}")

    exit_code = None
    if args.input_log is None:
        print(f"Starting consumer; combined output is being captured to {log_path}", flush=True)
        exit_code = run_consumer(command, log_path, quiet=args.quiet)
    elif not log_path.is_file():
        parser.error(f"input log does not exist: {log_path}")

    with log_path.open("r", encoding="utf-8", errors="replace") as log_file:
        outcomes, total = parse_log(log_file)
    shared_failure_reasons = reconcile_java_outcomes(log_path, outcomes)
    report_path.parent.mkdir(parents=True, exist_ok=True)
    with report_path.open("x", encoding="utf-8") as report_file:
        report_file.write(
            markdown_report(outcomes, total, log_path, exit_code, shared_failure_reasons)
        )
    print(f"Report written to {report_path}")
    return exit_code if exit_code not in (None, -signal.SIGINT, 130) else 0


if __name__ == "__main__":
    raise SystemExit(main())