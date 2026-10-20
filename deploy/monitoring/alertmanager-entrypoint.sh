#!/bin/sh
# Writes Alertmanager's config from environment variables, then starts it.
# Alertmanager can't read env vars itself, and webhook URLs are secrets, so
# they stay in .env.prod rather than in this repo.
#
#   MONITORING_ALERT_WEBHOOK  Alertmanager-format webhook: Grafana OnCall,
#                             Opsgenie / Squadcast / incident.io Prometheus
#                             integrations, or your own receiver.
#   MONITORING_SLACK_WEBHOOK  Slack incoming webhook URL.
#   MONITORING_PAGERDUTY_KEY  PagerDuty Events v2 routing (integration) key.
#
# Any combination works; with none set, alerts are visible only in the
# Prometheus and Alertmanager UIs.
set -eu

CONFIG=/tmp/alertmanager.yml

{
  echo "route:"
  echo "  receiver: oncall"
  echo "  group_by: [alertname]"
  echo "  group_wait: 30s"
  echo "  group_interval: 5m"
  echo "  repeat_interval: 3h"
  echo "  routes:"
  echo "    - matchers: ['severity=\"critical\"']"
  echo "      receiver: oncall"
  echo "      repeat_interval: 30m"
  echo "receivers:"
  echo "  - name: oncall"
  if [ -n "${MONITORING_ALERT_WEBHOOK:-}" ]; then
    echo "    webhook_configs:"
    echo "      - url: '${MONITORING_ALERT_WEBHOOK}'"
    echo "        send_resolved: true"
  fi
  if [ -n "${MONITORING_SLACK_WEBHOOK:-}" ]; then
    echo "    slack_configs:"
    echo "      - api_url: '${MONITORING_SLACK_WEBHOOK}'"
    echo "        send_resolved: true"
    echo "        title: '{{ .CommonLabels.severity | toUpper }}: {{ .CommonLabels.alertname }}'"
    echo "        text: '{{ range .Alerts }}{{ .Annotations.summary }}{{ \"\\n\" }}{{ end }}'"
  fi
  if [ -n "${MONITORING_PAGERDUTY_KEY:-}" ]; then
    echo "    pagerduty_configs:"
    echo "      - routing_key: '${MONITORING_PAGERDUTY_KEY}'"
    echo "        send_resolved: true"
  fi
} > "$CONFIG"

if [ -z "${MONITORING_ALERT_WEBHOOK:-}${MONITORING_SLACK_WEBHOOK:-}${MONITORING_PAGERDUTY_KEY:-}" ]; then
  echo "alertmanager: no MONITORING_* receiver set; alerts will not be sent anywhere" >&2
fi

exec /bin/alertmanager --config.file="$CONFIG" --storage.path=/alertmanager "$@"
