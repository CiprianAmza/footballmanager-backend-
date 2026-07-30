#!/usr/bin/env bash
set -euo pipefail

usage() {
  /bin/cat <<'EOF'
Usage:
  scripts/run-loc-outcome2-sweep.sh ATTACK_MIN ATTACK_MAX [STEP]

Sweeps ONE attack multiplier. The matchup exponent is held fixed (MATCHUP_EXPONENT)
so every difference you see comes from the multiplier alone.

Example (10 combinations, 1.1 .. 2.0 step 0.1):
  EDITIONS=1000 TOP_N=3 scripts/run-loc-outcome2-sweep.sh 1.1 2.0 0.1

Optional environment variables:
  ATTACK_TARGET  which multiplier is swept (default: refuses)
                   refuses      -> work-rate.traits.REFUSES_DEFENSIVE_WORK.attack-multiplier
                                   This is the knob behind the in-game "Stay Forward"
                                   player toggle: CanonicalRuntimeInputFactory maps that
                                   flag to the REFUSES_DEFENSIVE_WORK trait, and the trait
                                   rule fully replaces the instruction rule.
                   stay-forward -> work-rate.instructions.STAY_FORWARD.attack-multiplier
                                   Currently unreachable at runtime: nothing writes the
                                   "stay forward" slot-instruction text this path matches.
  MATCHUP_EXPONENT fixed probability.matchup-exponent for every run (default: 2.5)
  TEAM_IDS       comma-separated team IDs
  EDITIONS       editions per combination (default: 1000)
  LEG_FORMAT     single or two-leg (default: single)
  TOP_N          additionally retain the first N rows; 0 means none when FOCUS_REGEX is set,
                 otherwise 0 retains all (default: 0)
  FOCUS_REGEX    retain matching team rows, e.g. 'Tik Tok|Shadows'; rank 1 is always retained
  TACTIC_TEAM_ID team whose tactic is overridden (Tik Tok is 14 in the bootstrap data)
  MENTALITY      one value (1..5), a comma-separated list, or all/1-5/1..5
                  1=Very Defensive, 2=Defensive, 3=Balanced, 4=Attacking, 5=Very Attacking
  TEMPO          one value (1..5), a comma-separated list, or all/1-5/1..5
                  1=Much Lower, 2=Lower, 3=Standard, 4=Higher, 5=Much Higher
  ATTRIBUTE_TEAM_ID team receiving the simulated attribute delta (defaults to TACTIC_TEAM_ID)
  ATTRIBUTE      canonical attribute name, e.g. PACE, VISION, FINISHING
  ATTRIBUTE_DELTA signed non-zero integer added to all 11 selected starters, clamped to 1..20
  OUTPUT_DIR     output directory (default: target/loc-outcome-2-sweep)
  FAIL_FAST      1 stops at first failed Maven run; 0 continues (default: 1)

Live output:
  target/loc-outcome-2-sweep/live.md
  target/loc-outcome-2-sweep/results.csv

Watch while it runs:
  tail -f target/loc-outcome-2-sweep/live.md
EOF
}

if [[ $# -lt 2 || $# -gt 3 ]]; then
  usage >&2
  exit 2
fi

attack_min=$1
attack_max=$2
step=${3:-0.1}

attack_target=${ATTACK_TARGET:-refuses}
matchup_exponent=${MATCHUP_EXPONENT:-2.5}
case "$attack_target" in
  stay-forward)
    attack_property="match.engine.compartment.work-rate.instructions.STAY_FORWARD.attack-multiplier"
    ;;
  refuses)
    attack_property="match.engine.compartment.work-rate.traits.REFUSES_DEFENSIVE_WORK.attack-multiplier"
    ;;
  *)
    echo "ATTACK_TARGET must be 'stay-forward' or 'refuses'; got '$attack_target'" >&2
    exit 2
    ;;
esac

team_ids=${TEAM_IDS:-1,2,3,4,5,13,14,15,37,38,61,87,88,49,50,69}
editions=${EDITIONS:-1000}
leg_format=${LEG_FORMAT:-single}
top_n=${TOP_N:-0}
focus_regex=${FOCUS_REGEX:-}
tactic_team_id=${TACTIC_TEAM_ID:-}
mentality=${MENTALITY:-}
tempo=${TEMPO:-}
attribute_team_id=${ATTRIBUTE_TEAM_ID:-${tactic_team_id:-}}
attribute=${ATTRIBUTE:-}
attribute_delta=${ATTRIBUTE_DELTA:-}
output_dir=${OUTPUT_DIR:-target/loc-outcome-2-sweep}
fail_fast=${FAIL_FAST:-1}
test_name="LeagueOfChampionsOutcome2IT#simulateCanonicalLeagueOfChampionsAndReport"
lock_dir="target/.loc-outcome2-sweep.lock"

mkdir -p target
if ! mkdir "$lock_dir" 2>/dev/null; then
  echo "Another LeagueOfChampionsOutcome2IT sweep is already running." >&2
  echo "Wait for it to finish; all sweep instances share the same generated report." >&2
  exit 3
fi
cleanup_lock() {
  rmdir "$lock_dir" 2>/dev/null || true
}
trap cleanup_lock EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

team_count=$(/usr/bin/awk -F',' '
  {
    for (field_number = 1; field_number <= NF; field_number++) {
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", $field_number)
      if ($field_number != "" && !seen[$field_number]++) count++
    }
  }
  END { print count + 0 }
' <<< "$team_ids")
generated_report="target/loc-outcome-2-custom-${team_count}teams.md"

for value_name in attack_min attack_max matchup_exponent step; do
  value=${!value_name}
  if ! /usr/bin/awk -v value="$value" 'BEGIN { exit !(value ~ /^[-+]?[0-9]*\.?[0-9]+$/) }'; then
    echo "$value_name must be numeric; got '$value'" >&2
    exit 2
  fi
done
if ! /usr/bin/awk -v value="$step" 'BEGIN { exit !(value > 0) }'; then
  echo "STEP must be > 0; got '$step'" >&2
  exit 2
fi
if ! [[ "$editions" =~ ^[1-9][0-9]*$ ]]; then
  echo "EDITIONS must be a positive integer; got '$editions'" >&2
  exit 2
fi
if ! [[ "$top_n" =~ ^[0-9]+$ ]]; then
  echo "TOP_N must be a non-negative integer; got '$top_n'" >&2
  exit 2
fi
if [[ "$fail_fast" != "0" && "$fail_fast" != "1" ]]; then
  echo "FAIL_FAST must be 0 or 1; got '$fail_fast'" >&2
  exit 2
fi
if [[ -n "$tactic_team_id" || -n "$mentality" || -n "$tempo" ]]; then
  if ! [[ "$tactic_team_id" =~ ^[1-9][0-9]*$ ]]; then
    echo "TACTIC_TEAM_ID is required and must be a positive integer when overriding tactics" >&2
    exit 2
  fi
  if [[ -z "$mentality" && -z "$tempo" ]]; then
    echo "Set at least MENTALITY or TEMPO when TACTIC_TEAM_ID is present" >&2
    exit 2
  fi
fi
if [[ -n "$attribute" || -n "$attribute_delta" || -n "${ATTRIBUTE_TEAM_ID:-}" ]]; then
  if ! [[ "$attribute_team_id" =~ ^[1-9][0-9]*$ ]]; then
    echo "ATTRIBUTE_TEAM_ID (or TACTIC_TEAM_ID fallback) must be a positive integer" >&2
    exit 2
  fi
  if [[ -z "$attribute" ]]; then
    echo "ATTRIBUTE is required when configuring an attribute override" >&2
    exit 2
  fi
  if ! [[ "$attribute_delta" =~ ^[-+]?[0-9]+$ ]] || [[ "$attribute_delta" == "0" ]]; then
    echo "ATTRIBUTE_DELTA must be a signed non-zero integer; got '$attribute_delta'" >&2
    exit 2
  fi
fi

expand_tactic_values() {
  local axis_name=$1
  local raw_value=$2
  local normalized
  if [[ -z "$raw_value" ]]; then
    echo persisted
    return
  fi
  case "$raw_value" in
    all|1-5|1..5)
      /usr/bin/printf '1\n2\n3\n4\n5\n'
      return
      ;;
  esac
  normalized=${raw_value//,/ }
  for value in $normalized; do
    if ! [[ "$value" =~ ^[1-5]$ ]]; then
      echo "$axis_name must contain only values in [1,5], or use all/1-5/1..5; got '$raw_value'" >&2
      exit 2
    fi
    echo "$value"
  done
}

mentality_values=$(expand_tactic_values MENTALITY "$mentality")
tempo_values=$(expand_tactic_values TEMPO "$tempo")

mkdir -p "$output_dir/reports" "$output_dir/logs"
live_file="$output_dir/live.md"
csv_file="$output_dir/results.csv"

attack_values=$(/usr/bin/awk -v min="$attack_min" -v max="$attack_max" -v step="$step" '
  BEGIN {
    if (min > max) exit 2
    epsilon = step / 1000000.0
    for (value = min; value <= max + epsilon; value += step) printf "%.10g\n", value
  }')
if [[ -z "$attack_values" ]]; then
  echo "Invalid range: minimum must be <= maximum" >&2
  exit 2
fi

attack_count=$(wc -l <<<"$attack_values" | tr -d ' ')
mentality_count=$(wc -l <<<"$mentality_values" | tr -d ' ')
tempo_count=$(wc -l <<<"$tempo_values" | tr -d ' ')
total=$((attack_count * mentality_count * tempo_count))

{
  echo "# LeagueOfChampionsOutcome2IT parameter sweep"
  echo
  echo "Started: $(date '+%Y-%m-%d %H:%M:%S %Z')"
  echo "Team IDs: $team_ids"
  echo "Editions per combination: $editions"
  echo "Swept property: $attack_property"
  echo "Attack multiplier: $attack_min .. $attack_max"
  echo "Matchup exponent (fixed): $matchup_exponent"
  echo "Step: $step"
  echo "Combinations: $total"
  echo "Filter: ${focus_regex:-top $top_n (0 = all)}"
  echo "Tactic override: team=${tactic_team_id:-none}, mentality=${mentality:-persisted}, tempo=${tempo:-persisted}"
  echo "Attribute override: team=${attribute_team_id:-none}, attribute=${attribute:-none}, delta=${attribute_delta:-none}"
  echo
} > "$live_file"

echo 'attack_multiplier,matchup_exponent,mentality,tempo,rank,team,top_xi,gk,attack,midfield,defense,final_attack,final_protection,tactic,trophies,reach_group,qualify,avg_group_position,avg_group_points,final,semi,qf,ko_won' > "$csv_file"

completed=0
failures=0
while IFS= read -r attack_multiplier; do
  while IFS= read -r current_mentality; do
    while IFS= read -r current_tempo; do
    completed=$((completed + 1))
    key="attack-${attack_multiplier}_exponent-${matchup_exponent}_mentality-${current_mentality}_tempo-${current_tempo}"
    log_file="$output_dir/logs/$key.log"
    report_file="$output_dir/reports/$key.md"

    {
      echo "## [$completed/$total] attack=$attack_multiplier, exponent=$matchup_exponent, mentality=$current_mentality, tempo=$current_tempo"
      echo
      echo "Status: **RUNNING** — $(date '+%Y-%m-%d %H:%M:%S %Z')"
      echo
    } >> "$live_file"

    command=(mvn -q verify -Ptune
      "-Dit.test=$test_name"
      "-Dteam.ids=$team_ids"
      "-Dloc.outcome2.editions=$editions"
      "-Dleg.format=$leg_format"
      "-D$attack_property=$attack_multiplier"
      "-Dmatch.engine.compartment.probability.matchup-exponent=$matchup_exponent")
    if [[ -n "$tactic_team_id" ]]; then
      command+=("-Dloc.outcome2.team-id=$tactic_team_id")
      if [[ "$current_mentality" != "persisted" ]]; then
        command+=("-Dloc.outcome2.mentality=$current_mentality")
      fi
      if [[ "$current_tempo" != "persisted" ]]; then
        command+=("-Dloc.outcome2.tempo=$current_tempo")
      fi
    fi
    if [[ -n "$attribute" ]]; then
      command+=("-Dloc.outcome2.attribute-team-id=$attribute_team_id")
      command+=("-Dloc.outcome2.attribute=$attribute")
      command+=("-Dloc.outcome2.attribute-delta=$attribute_delta")
    fi

    # A successful run must create a fresh report; never copy a stale result from a previous combo.
    rm -f "$generated_report"
    started_at=$(date +%s)
    if "${command[@]}" > "$log_file" 2>&1; then
      elapsed=$(( $(date +%s) - started_at ))
      if [[ ! -f "$generated_report" ]]; then
        echo "Expected report missing: $generated_report" >> "$log_file"
        status=1
      else
        cp "$generated_report" "$report_file"
        status=0
      fi
    else
      status=$?
      elapsed=$(( $(date +%s) - started_at ))
    fi

    if [[ $status -ne 0 ]]; then
      failures=$((failures + 1))
      {
        echo "Status: **FAILED** after ${elapsed}s. Full log: \`$log_file\`"
        echo
        echo '```text'
        tail -n 30 "$log_file"
        echo '```'
        echo
      } >> "$live_file"
      if [[ "$fail_fast" == "1" ]]; then
        echo "Sweep stopped at $key. See $live_file and $log_file" >&2
        exit "$status"
      fi
      continue
    fi

    {
      echo "Status: **PASS** in ${elapsed}s. Full report: \`$report_file\`"
      echo
      /usr/bin/awk -v focus="$focus_regex" -v top_n="$top_n" '
        /^\| Rank \| Team / { in_table=1; print; next }
        in_table && /^\|[-:| ]+\|$/ { print; next }
        in_table && /^\|[[:space:]]*[0-9]+[[:space:]]*\|/ {
          split($0, fields, "|")
          rank=fields[2]
          gsub(/[[:space:]]/, "", rank)
          if (focus != "") {
            if (rank + 0 == 1 || $0 ~ focus || (top_n > 0 && rank + 0 <= top_n)) print
          } else if (top_n == 0 || rank + 0 <= top_n) print
          next
        }
        in_table { exit }
      ' "$report_file"
      echo
    } >> "$live_file"

    /usr/bin/awk -F'|' -v attack_multiplier="$attack_multiplier" -v matchup_exponent="$matchup_exponent" \
        -v mentality="$current_mentality" -v tempo="$current_tempo" \
        -v focus="$focus_regex" -v top_n="$top_n" '
      function trim(value) { gsub(/^[[:space:]]+|[[:space:]]+$/, "", value); return value }
      function csv(value) { gsub(/"/, "\"\"", value); return "\"" value "\"" }
      /^\|[[:space:]]*[0-9]+[[:space:]]*\|/ {
        rank=trim($2)
        if (focus != "") {
          include = rank + 0 == 1 || $0 ~ focus || (top_n > 0 && rank + 0 <= top_n)
        } else {
          include = top_n == 0 || rank + 0 <= top_n
        }
        if (!include) next
        printf "%s,%s,%s,%s", attack_multiplier, matchup_exponent, mentality, tempo
        for (column=2; column<=20; column++) printf ",%s", csv(trim($column))
        printf "\n"
      }
    ' "$report_file" >> "$csv_file"

    echo "[$completed/$total] PASS attack=$attack_multiplier exponent=$matchup_exponent mentality=$current_mentality tempo=$current_tempo (${elapsed}s)"
    done <<< "$tempo_values"
  done <<< "$mentality_values"
done <<< "$attack_values"

{
  echo "# Sweep completed"
  echo
  echo "Finished: $(date '+%Y-%m-%d %H:%M:%S %Z')"
  echo "Combinations: $total; failures: $failures"
  echo "CSV: \`$csv_file\`"
} >> "$live_file"

echo "Sweep complete: $total combinations, $failures failures"
echo "Live Markdown: $live_file"
echo "CSV: $csv_file"
