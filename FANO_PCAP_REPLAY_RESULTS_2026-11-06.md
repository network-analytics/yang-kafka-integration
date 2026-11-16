# FANO PCAP Replay Validation Results

**Replay date:** 2026-11-06  
**Topic:** `telemetry-yang`  
**Consumer log:** `/bp2/data/consumer-validation.log`  
**Runtime JAR MD5:** `1a120a964c76fa0f7b034142d8ac68d3`

## Summary

The consumer log contains 5,886 payloads: 5,695 deserialization failures and 191 successfully consumed records. Of these, 5,881 records belong to the requested FANO subscription range 1-18: 5,690 failed and 191 passed. The other five records had subscription IDs 20, 22, 24, and 26; they are excluded from this FANO table.

All 191 successful FANO records are from subscriptions 16-18. Subscriptions 7, 9, and 11 have no datastore-update payload in the consumer log. The provided subscription 9 sample is a `subscription-started` control notification, not an update to validate. No payload sample for 7 or 11 was present in the captured consumer log.

Payload examples below are short excerpts from the logged raw JSON. `...` indicates omitted fields, not literal payload content.

## Per-Subscription Results

| Subscription | XPath filter | Example payload excerpt | Result and reason | Failure evidence from replay log |
|---|---|---|---|---|
| 1 | `/ietf-interfaces:interfaces/interface[type='iana-if-type:ethernetCsmacd']/statistics` | `interface: [{name: "ethernetCsmacd.0.18.0", statistics: {...}}]` | **Failed: 261/261.** The selected statistics lack a mandatory timestamp; related `when` checks also fail without the parent `type`. | Schema Registry `ietf-interfaces` v2 marks `statistics/discontinuity-time` mandatory. The payload includes counters but omits it. The YANG description requires the subsystem-restart time if no counter discontinuity occurred. Validator: `missing mandatory schema node:if:discontinuity-time`. |
| 2 | `/ietf-interfaces:interfaces/interface[type='iana-if-type:gpon']/statistics` | `interface: [{name: "gpon.0.1.0", statistics: {...}}]` | **Failed: 35/35.** The selected statistics lack a mandatory timestamp; related `when` checks also fail without the parent `type`. | Schema Registry `ietf-interfaces` v2 marks `statistics/discontinuity-time` mandatory. The payload includes counters but omits it. The YANG description requires the subsystem-restart time if no counter discontinuity occurred. Validator: `missing mandatory schema node:if:discontinuity-time`. |
| 3 | `/ietf-interfaces:interfaces/interface[type='bbf-xpon-if-type:channel-pair']/statistics` | `interface: [{name: "channel-pair.0.1.0.xgs", statistics: {...}}]` | **Failed: 36/36.** The selected statistics lack a mandatory timestamp; related `when` checks also fail without the parent `type`. | Schema Registry `ietf-interfaces` v2 marks `statistics/discontinuity-time` mandatory. The payload includes counters but omits it. The YANG description requires the subsystem-restart time if no counter discontinuity occurred. Validator: `missing mandatory schema node:if:discontinuity-time`. |
| 4 | `/ietf-interfaces:interfaces/interface[type='bbf-xpon-if-type:v-ani']/statistics` | `interface: [{name: "v-ani.0.1.0.0", statistics: {...}}]` | **Failed: 36/36.** The selected statistics lack a mandatory timestamp; related `when` checks also fail without the parent `type`. | Schema Registry `ietf-interfaces` v2 marks `statistics/discontinuity-time` mandatory. The payload includes counters but omits it. The YANG description requires the subsystem-restart time if no counter discontinuity occurred. Validator: `missing mandatory schema node:if:discontinuity-time`. |
| 5 | `/ietf-interfaces:interfaces-state/interface/bbf-qos-traffic-mngt-state:tm-root/queues/queue/statistics` | `interface: [{name: "channel-termination.0.19.0.0", tm-root: {queues: {...}}}]` | **Failed: 4,959/4,959.** Filter omits mandatory interface siblings outside the selected queue subtree. | Schema Registry models require interface `oper-status`, `type`, `statistics`, and xPON `channel-group`. This payload has `name` plus the selected `tm-root/queues/queue/statistics` subtree only. Validator: `data-missing` for `if:oper-status`, `if:type`, `if:statistics`, and `bbf-xpon:channel-group`. |
| 6 | `/ietf-hardware:hardware/component[huawei-hardware:sub-class='huawei-hardware-types:ethernetCsmacd-xcvr']/bbf-hardware-transceivers:transceiver` | `sub-class: "huawei-hardware-types:ethernetCsmacd-xcvr"` | **Failed: 174/174.** The value does not satisfy the registered four-base identityref. | Schema Registry `huawei-hardware` v1 requires `sub-class` to derive from transceiver, transceiver-link, chassis, and port. `huawei-hardware-types` v1 defines this payload identity with only the transceiver base. Validator: `invalid value:huawei-hardware-types:ethernetCsmacd-xcvr`. |
| 7 | Not observed | No data-update sample captured. | **Not evaluated.** No subscription 7 data-update payload appeared in the consumer log. | N/A: no failed update record captured. |
| 8 | `/ietf-hardware:hardware/component[huawei-hardware:sub-class='huawei-hardware-types:pon-xcvr']/bbf-hardware-transceivers:transceiver` | `sub-class: "huawei-hardware-types:pon-xcvr"` | **Failed: 34/34.** The value does not satisfy the registered four-base identityref. | Schema Registry `huawei-hardware` v1 requires four bases. `huawei-hardware-types` v1 defines `pon-xcvr` with only the transceiver base. Validator: `invalid value:huawei-hardware-types:pon-xcvr`. |
| 9 | `/ietf-hardware:hardware/component[huawei-hardware:sub-class='huawei-hardware-types:pon-xcvr-link']/bbf-hardware-transceivers:transceiver-link` | Control sample: `subscription-started` for ID 9; see exact control message below. | **Not evaluated.** The supplied record is a control notification, not a datastore update. | N/A: no failed update record captured. |
| 10 | `/ietf-hardware:hardware/component[huawei-hardware:sub-class='huawei-hardware-types:ont-ani-xcvr']/bbf-hardware-transceivers:transceiver` | `sub-class: "huawei-hardware-types:ont-ani-xcvr"` | **Failed: 34/34.** The value does not satisfy the registered four-base identityref. | Schema Registry `huawei-hardware` v1 requires four bases. `huawei-hardware-types` v1 defines `ont-ani-xcvr` with only the transceiver base. Validator: `invalid value:huawei-hardware-types:ont-ani-xcvr`. |
| 11 | Not observed | No data-update sample captured. | **Not evaluated.** No subscription 11 data-update payload appeared in the consumer log. | N/A: no failed update record captured. |
| 12 | `/ietf-interfaces:interfaces-state/interface/bbf-xponani:ani` | `interface: [{name: "ani.0.1.0.0", bbf-xponani:ani: {...}}]` | **Failed: 34/34.** The filtered state payload omits mandatory interface fields and siblings. | Schema Registry interface models require `type`, `oper-status`, `statistics`, and `channel-group` for the affected node. This payload has the selected `ani` subtree only. Validator: `data-missing` for `if:oper-status`, `if:type`, `if:statistics`, and `bbf-xpon:channel-group`. |
| 13 | `/ietf-interfaces:interfaces-state/interface[type='bbf-xpon-if-type:v-ani']/statistics/bbf-xpon-statistics-management:xpon/phy` | `interface: [{name: "v-ani.0.1.0.0", statistics: {xpon: {phy: {...}}}]` | **Failed: 35/35.** `type` and `oper-status` are missing; associated `when` checks fail. | Schema Registry `ietf-interfaces` v2 requires interface `type` and `oper-status`. Payload has `name` and the selected PHY statistics only. Validator: `data-missing` for `if:type`, `if:oper-status`, `if:discontinuity-time`; `bad-element` because the v-ani PHY `when` condition is false without `type`. |
| 14 | `/ietf-interfaces:interfaces-state/interface[type='bbf-xpon-if-type:ani']/statistics/bbf-xpon-statistics-management:xpon/phy` | `interface: [{name: "ani.0.1.0.0", statistics: {xpon: {phy: {...}}}]` | **Failed: 35/35.** `type` and `oper-status` are missing; associated `when` checks fail. | Schema Registry `ietf-interfaces` v2 requires interface `type` and `oper-status`. Payload has `name` and the selected PHY statistics only. Validator: `data-missing` for `if:type`, `if:oper-status`, `if:discontinuity-time`; `bad-element` because the ANI PHY statistics `when` condition is false without `type`. |
| 15 | `/ietf-hardware:hardware/component/huawei-energy-management-an:energy-consumption` | `component: [{name: "chassis.0", energy-consumption: {...}}]` | **Failed: 17/17.** The component omits mandatory `hw:class`. | Schema Registry `ietf-hardware` requires component `class`. Payload component contains `name` and `energy-consumption`, but no `class`. Validator: `data-missing` at component `chassis.0`, `missing mandatory schema node:hw:class`. |
| 16 | `/ietf-hardware:hardware/component/bbf-hardware-cpu:cpu-processor-data` | `component: [{class: "iana-hardware:cpu", name: "cpu.0.18", cpu-processor-data: {...}}]` | **Passed: 87/87.** | N/A: no failure. |
| 17 | `/ietf-hardware:hardware/component[class='iana-hardware:sensor']/sensor-data[value-type='celsius']` | `component: [{class: "iana-hardware:sensor", name: "sensor.celsius.0.18", sensor-data: {value-type: "celsius", value: 27}}]` | **Passed: 87/87.** | N/A: no failure. |
| 18 | `/ietf-hardware:hardware/component[class='iana-hardware:sensor']/sensor-data[value-type='rpm']` | `component: [{class: "iana-hardware:sensor", name: "sensor.rpm.0.0", sensor-data: {value-type: "rpm", value: 2550}}]` | **Passed: 17/17.** | N/A: no failure. |

## Schema Registry Evidence

The following commands query the live Schema Registry at `10.190.33.167:8081`:

```bash
curl -fsS http://10.190.33.167:8081/schemas/ids/983 | jq '{schemaType, references: (.references | length)}'
curl -fsS http://10.190.33.167:8081/schemas/ids/983/versions | jq .

curl -fsS http://10.190.33.167:8081/schemas/ids/985 | jq '{schemaType, references: (.references | length)}'
curl -fsS http://10.190.33.167:8081/schemas/ids/985/versions | jq .
```

Observed output:

```text
schema ID 983: type YANG; 128 references
subject netcalyx.labietf-telemetry-message, version 4
schema ID 985: type YANG; 129 references
subject netcalyx.labietf-telemetry-message, version 5
```

Relevant reference entries were returned by both schemas:

```text
huawei-bbf-hardware-transceivers-deviations-MA5800
  -> netcalyx.labhuawei-bbf-hardware-transceivers-deviations-MA5800, version 1
huawei-bbf-xpon-deviations-MA5800
  -> netcalyx.labhuawei-bbf-xpon-deviations-MA5800, version 1
huawei-hardware
  -> netcalyx.labhuawei-hardware, version 1
huawei-ietf-interfaces-deviations-MA5800
  -> netcalyx.labhuawei-ietf-interfaces-deviations-MA5800, version 1
```

Schema 985 additionally references `huawei-hardware-types` version 1, which supplies the identities rejected in subscriptions 6, 8, and 10.

To inspect the exact registered YANG declarations:

```bash
curl -fsS 'http://10.190.33.167:8081/subjects/netcalyx.labietf-interfaces/versions/2' \
  | jq -r '.schema' | grep -A8 'leaf type {'

curl -fsS 'http://10.190.33.167:8081/subjects/netcalyx.labietf-interfaces/versions/2' \
  | jq -r '.schema' | grep -A12 'leaf discontinuity-time {'

curl -fsS 'http://10.190.33.167:8081/subjects/netcalyx.labhuawei-hardware/versions/1' \
  | jq -r '.schema' | grep -A10 'leaf sub-class {'

curl -fsS 'http://10.190.33.167:8081/subjects/netcalyx.labhuawei-hardware-types/versions/1' \
  | jq -r '.schema' | grep -A4 -E 'identity (ethernetCsmacd-xcvr|pon-xcvr|ont-ani-xcvr) {'
```

Observed schema excerpts:

```yang
leaf type {
  type identityref { base interface-type; }
  mandatory true;
}

leaf sub-class {
  type identityref {
    base bbf-hwt:transceiver;
    base bbf-hwt:transceiver-link;
    base ianahw:chassis;
    base ianahw:port;
  }
}

identity ethernetCsmacd-xcvr { base bbf-hwt:transceiver; }
identity pon-xcvr { base bbf-hwt:transceiver; }
identity ont-ani-xcvr { base bbf-hwt:transceiver; }
```

The Schema Registry YANG declarations therefore match the observed rejection: `if:type` is mandatory, and each rejected transceiver identity has only one of the four bases required by `huawei-hardware:sub-class`. This is a filtered-payload/schema constraint mismatch for subscriptions 1-5 and 12-15, and an identity/model mismatch for 6, 8, and 10. The log does not demonstrate a deserializer crash or malformed JSON for these records.

## Replay Log Evidence

Commands used inside the AWE `app` container:

```bash
stat -c 'modified=%y bytes=%s' /bp2/data/consumer-validation.log
grep -c '^Deserializing payload:' /bp2/data/consumer-validation.log
grep -c 'YANG_DESERIALIZATION_FAILED' /bp2/data/consumer-validation.log
grep -c '^Message consumed:' /bp2/data/consumer-validation.log
grep 'YANG_DESERIALIZATION_FAILED' /bp2/data/consumer-validation.log \
  | sed -E 's/.*schema-id=([0-9]+).*/schema-id=\\1/' | sort | uniq -c
```

Observed output for the captured run:

```text
modified=2026-11-06 11:16:16.334000000 +0000 bytes=88619664
Deserializing payload lines=5886
YANG_DESERIALIZATION_FAILED lines=5695
Message consumed lines=191
schema-id=983 failures=5453
schema-id=985 failures=242
```

The total includes five records with subscription IDs 20, 22, 24, and 26, excluded from the FANO 1-18 table. For IDs 1-18, the per-subscription payload/failure/pass counts are listed in the table above. The full raw payload logging makes this file approximately 88.6 MB; apply suitable access controls and retention before using this logging level in longer-running environments.

## Interpretation and Caveats

- A failure means strict parsing or validation returned an error; PR15 logs the error and returns `null` so the consumer can continue. It does not relax validation.
- `if:type`, `if:oper-status`, and other absent mandatory siblings are not supplied by these filtered datastore-update payloads. Treating them as partial data would change validation semantics and was not enabled for this run.
- The reported parse-stage identity errors are invalid YANG identityref values against the registered schema, not malformed JSON.
- The consumer uses the legacy Schema Registry path rather than a YANG Library cache. Its feature-selection behavior should be considered separately; the evidence here does not show that changing it would make the missing mandatory data or rejected identities valid.
- The provider currently ignores the return value of `YangSchemaContext.validate()`. This is a separate schema-context validation concern, not a demonstrated cause of the payload errors above.
