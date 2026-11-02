#!/usr/bin/env bash
# schwab-box-quotes.sh — current box-spread offerings from the Schwab Trader API.
#
# For every expiration it searches all quoted strike pairs and prints:
#   LONG box  (buy the box = you LEND):   cheapest box per $ of width at natural prices
#   SHORT box (sell the box = you BORROW): richest box per $ of width at natural prices
# with the implied annual rates, and the same-maturity Treasury yield for comparison.
#
# Natural prices = pay the ask on legs you buy, receive the bid on legs you sell, using
# each leg's own NBBO. Box orders entered as one 4-leg limit order usually fill better
# than this, so treat these as the worst case and work a limit order toward the mid.
#
# Requires: bash, curl, jq, GNU date (standard on Linux).
#
# Usage:
#   ./schwab-box-quotes.sh login               # once a week (Schwab refresh tokens last 7 days)
#   ./schwab-box-quotes.sh [options]           # print current offerings
#
# Options:
#   -s SYMBOL     underlying index (default $SPX; also \$XSP, \$NDX, \$RUT)
#   -m DAYS       minimum days to expiry (default 7)
#   -d DAYS       maximum days to expiry (default 800)
#   -w POINTS     minimum box width (default 500; wider boxes spread costs over more face value)
#   -n COUNT      strikes above/below the money to request per expiry (default 50)
#   -c            CSV output instead of a table
#   -S DIR        also save the raw Schwab chain responses to DIR
#   -f FILE       analyze a saved chain response (repeatable) instead of calling Schwab
#   -T            skip the Treasury comparison
#   -h            help
#
# Credentials: env SCHWAB_APP_KEY / SCHWAB_APP_SECRET / SCHWAB_CALLBACK_URL, or the
# config written by `boxyield_collector.py setup` (~/.boxyield/config.json). Tokens are
# stored in ~/.boxyield/tokens.json, shared with boxyield_collector.py. Set
# BOXYIELD_HOME to use a different directory.
#
# Not investment or tax advice.

set -euo pipefail

API="https://api.schwabapi.com"
HOME_DIR="${BOXYIELD_HOME:-$HOME/.boxyield}"
CONFIG="$HOME_DIR/config.json"
TOKENS="$HOME_DIR/tokens.json"

die() { echo "error: $*" >&2; exit 1; }
note() { echo "$*" >&2; }

for tool in curl jq date; do
    command -v "$tool" >/dev/null || die "$tool is required (sudo apt install $tool)"
done

cfg() {  # cfg ENV_NAME json_key default
    local v="${!1:-}"
    if [[ -z "$v" && -f "$CONFIG" ]]; then v="$(jq -r --arg k "$2" '.[$k] // empty' "$CONFIG")"; fi
    echo "${v:-$3}"
}
APP_KEY="$(cfg SCHWAB_APP_KEY app_key "")"
APP_SECRET="$(cfg SCHWAB_APP_SECRET app_secret "")"
CALLBACK="$(cfg SCHWAB_CALLBACK_URL callback_url "https://127.0.0.1")"

now() { date +%s; }

# ---------------------------------------------------------------------------- auth

token_post() {  # token_post form-args... ; prints JSON response
    [[ -n "$APP_KEY" && -n "$APP_SECRET" ]] ||
        die "set SCHWAB_APP_KEY and SCHWAB_APP_SECRET (or run boxyield_collector.py setup)"
    local out
    out="$(curl -sS -u "$APP_KEY:$APP_SECRET" -X POST "$API/v1/oauth/token" \
        -H 'Content-Type: application/x-www-form-urlencoded' "$@")" || die "token request failed"
    jq -e '.access_token' >/dev/null 2>&1 <<<"$out" || die "Schwab rejected the token request: $out"
    echo "$out"
}

save_tokens() {  # save_tokens JSON refresh_expires_at
    mkdir -p "$HOME_DIR"
    local tmp
    tmp="$(umask 077; mktemp "$HOME_DIR/tokens.XXXXXX")"
    jq --argjson now "$(now)" --argjson rexp "$2" \
        '. + {access_expires_at: ($now + (.expires_in // 1800)), refresh_expires_at: $rexp}' <<<"$1" >"$tmp"
    chmod 600 "$tmp"
    mv "$tmp" "$TOKENS"
}

cmd_login() {
    local url redirected code
    url="$API/v1/oauth/authorize?client_id=$(jq -rn --arg v "$APP_KEY" '$v|@uri')&redirect_uri=$(jq -rn --arg v "$CALLBACK" '$v|@uri')"
    [[ -n "$APP_KEY" ]] || die "set SCHWAB_APP_KEY / SCHWAB_APP_SECRET first"
    echo "1. Open this URL, sign in to Schwab and approve access:"
    echo "   $url"
    command -v xdg-open >/dev/null && xdg-open "$url" >/dev/null 2>&1 || true
    echo "2. Your browser then goes to $CALLBACK… and shows an error page. That's expected."
    read -r -p "3. Paste the full address from the address bar: " redirected
    code="$(sed -n 's/.*[?&]code=\([^&]*\).*/\1/p' <<<"$redirected")"
    [[ -n "$code" ]] || die "no code= found in that address"
    code="$(printf '%b' "${code//%/\\x}")"   # URL-decode (codes end in %40)
    save_tokens "$(token_post -d grant_type=authorization_code --data-urlencode "code=$code" \
        --data-urlencode "redirect_uri=$CALLBACK")" "$(( $(now) + 7 * 86400 ))"
    echo "Logged in. Run login again before $(date -d "@$(( $(now) + 7 * 86400 ))" '+%a %b %d %H:%M')."
}

access_token() {
    [[ -f "$TOKENS" ]] || die "not logged in; run: $0 login"
    local t; t="$(cat "$TOKENS")"
    if (( $(jq -r '.access_expires_at // 0 | floor' <<<"$t") - 60 > $(now) )); then
        jq -r .access_token <<<"$t"; return
    fi
    local rexp; rexp="$(jq -r '.refresh_expires_at // 0 | floor' <<<"$t")"
    (( rexp > $(now) )) || die "Schwab login expired (7-day limit); run: $0 login"
    local new
    new="$(token_post -d grant_type=refresh_token --data-urlencode "refresh_token=$(jq -r .refresh_token <<<"$t")")"
    new="$(jq --arg rt "$(jq -r .refresh_token <<<"$t")" '.refresh_token //= $rt' <<<"$new")"
    save_tokens "$new" "$rexp"
    jq -r .access_token <<<"$new"
}

# ---------------------------------------------------------------------------- data

fetch_chain() {  # fetch_chain TOKEN FROM TO OUTFILE
    local attempt code
    for attempt in 1 2 3 4; do
        code="$(curl -sS -o "$4" -w '%{http_code}' -G "$API/marketdata/v1/chains" \
            -H "Authorization: Bearer $1" -H 'Accept: application/json' \
            --data-urlencode "symbol=$SYMBOL" --data-urlencode "contractType=ALL" \
            --data-urlencode "strikeCount=$STRIKES" --data-urlencode "includeUnderlyingQuote=true" \
            --data-urlencode "fromDate=$2" --data-urlencode "toDate=$3")" || code=000
        [[ "$code" == 200 ]] && return 0
        if [[ "$code" =~ ^(000|429|5..)$ && $attempt -lt 4 ]]; then sleep $(( attempt * 3 )); continue; fi
        die "Schwab chains HTTP $code: $(head -c 300 "$4")"
    done
}

# Latest Treasury par curve: prints "DATE" then JSON [[days, yield%], ...]; nothing on failure.
treasury_curve() {
    local y csv
    for y in "$(date +%Y)" "$(( $(date +%Y) - 1 ))"; do
        csv="$(curl -sS --max-time 30 -A boxyield \
            "https://home.treasury.gov/resource-center/data-chart-center/interest-rates/daily-treasury-rates.csv/$y/all?type=daily_treasury_yield_curve&field_tdr_date_value=$y&page&_format=csv" \
            2>/dev/null)" || continue
        csv="$(awk -F, '
            BEGIN { t["1 MO"]=30; t["1.5 MONTH"]=45; t["2 MO"]=61; t["3 MO"]=91; t["4 MO"]=122; t["6 MO"]=182
                    t["1 YR"]=365; t["2 YR"]=730; t["3 YR"]=1095; t["5 YR"]=1826; t["7 YR"]=2557
                    t["10 YR"]=3652; t["20 YR"]=7305; t["30 YR"]=10957 }
            { gsub(/"|\r/, "") }
            NR == 1 { for (i = 1; i <= NF; i++) h[i] = toupper($i); next }
            NR == 2 { printf "%s", $1; for (i = 2; i <= NF; i++) if ((h[i] in t) && $i != "") printf " %s:%s", t[h[i]], $i; exit }
        ' <<<"$csv")"
        [[ "$csv" == */* ]] || continue
        echo "${csv%% *}"
        jq -nc --arg s "${csv#* }" '[$s | split(" ")[] | split(":") | map(tonumber)] | sort'
        return
    done
}

# jq program: chain responses (slurped) -> one row per expiration with the best boxes.
read -r -d '' ANALYZE <<'JQ' || true
def bey($price; $width; $days):          # bond-equivalent yield %, like Treasury's CMT curve
  ($width / $price) as $g
  | if $days <= 182 then ($g - 1) * 365 / $days * 100
    else 2 * (pow($g; 1 / (2 * $days / 365)) - 1) * 100 end;
def tsy($d):
  if ($curve | length) == 0 then null
  elif $d <= $curve[0][0] then $curve[0][1]
  elif $d >= $curve[-1][0] then $curve[-1][1]
  else ([range(0; ($curve | length) - 1) | select($curve[.][0] <= $d and $d <= $curve[. + 1][0])][0]) as $i
    | $curve[$i][1] + ($curve[$i + 1][1] - $curve[$i][1]) * ($d - $curve[$i][0]) / ($curve[$i + 1][0] - $curve[$i][0])
  end;
def r2: . * 100 | round / 100;
def r3: . * 1000 | round / 1000;

($today + "T00:00:00Z" | fromdateiso8601) as $t0
| [ .[] as $resp
    | (["callExpDateMap", "C"], ["putExpDateMap", "P"]) as [$map, $side]
    | ($resp[$map] // {}) | to_entries[]
    | (.key | split(":")[0]) as $exp
    | .value | to_entries[] | .value[]
    | { root: ((.optionRoot // (.symbol | split(" ")[0])) | ascii_upcase), exp: $exp,
        k: (.strikePrice | tonumber), side: $side, bid: (.bid // 0), ask: (.ask // 0) }
    | select(.bid > 0 and .ask >= .bid) ]
| unique_by([.root, .exp, .k, .side])
| group_by([.root, .exp])
| map(
    .[0] as $f
    | ((($f.exp + "T00:00:00Z" | fromdateiso8601) - $t0) / 86400 | round) as $days
    | select($days >= $mindays and $days <= $maxdays)
    # strikes that have both a call and a put quote
    | [ group_by(.k)[] | select(length == 2)
        | { k: .[0].k, c: (map(select(.side == "C"))[0]), p: (map(select(.side == "P"))[0]) } ]
    | sort_by(.k) as $ks
    | [ range(0; $ks | length) as $i | range($i + 1; $ks | length) as $j
        | $ks[$i] as $lo | $ks[$j] as $hi | ($hi.k - $lo.k) as $w
        | select($w >= $minwidth)
        | { lo: $lo.k, hi: $hi.k, w: $w,
            long:  ($lo.c.ask - $hi.c.bid + $hi.p.ask - $lo.p.bid),
            short: ($lo.c.bid - $hi.c.ask + $hi.p.bid - $lo.p.ask) } ] as $pairs
    | ([$pairs[] | select(.long > 0 and .long < .w * 1.05)] | min_by(.long / .w)) as $L
    | ([$pairs[] | select(.short > 0 and .short < .w)] | max_by(.short / .w)) as $S
    | select($L != null or $S != null)
    | tsy($days) as $u
    | { root: $f.root, expiry: $f.exp, days: $days, strikes: ($ks | length),
        long_lo: $L.lo, long_hi: $L.hi, long_price: ($L.long | if . then r2 else null end),
        long_yield: (if $L then bey($L.long; $L.w; $days) | r3 else null end),
        short_lo: $S.lo, short_hi: $S.hi, short_price: ($S.short | if . then r2 else null end),
        short_rate: (if $S then bey($S.short; $S.w; $days) | r3 else null end),
        treasury: ($u | if . then r3 else null end) }
    | .lend_spread_bp  = (if .long_yield and .treasury then (.long_yield - .treasury) * 100 | round else null end)
    | .borrow_spread_bp = (if .short_rate and .treasury then (.short_rate - .treasury) * 100 | round else null end)
  )
| sort_by(.days, .root)
JQ

print_table() {
    jq -r '.[] | [.expiry, .days, .root,
                  (if .long_lo then "\(.long_lo)-\(.long_hi)" else "-" end), .long_price, .long_yield, .lend_spread_bp,
                  (if .short_lo then "\(.short_lo)-\(.short_hi)" else "-" end), .short_price, .short_rate, .borrow_spread_bp,
                  .treasury] | map(. // "-") | @tsv' |
    awk -F'\t' '
        function n(x, f) { return x == "-" ? "-" : sprintf(f, x) }
        BEGIN {
            printf "%-10s %5s %-5s | %-11s %9s %7s %6s | %-11s %9s %7s %6s | %6s\n",
                   "EXPIRY", "DAYS", "ROOT", "LONG BOX", "COST", "LEND%", "vsUST", "SHORT BOX", "CREDIT", "BORR%", "vsUST", "UST%"
            printf "%-10s %5s %-5s | %-11s %9s %7s %6s | %-11s %9s %7s %6s | %6s\n",
                   "", "", "", "(buy=lend)", "", "", "bp", "(sell=borrow)", "", "", "bp", ""
        }
        { printf "%-10s %5s %-5s | %-11s %9s %7s %6s | %-11s %9s %7s %6s | %6s\n",
                 $1, $2, $3, $4, n($5, "%.2f"), n($6, "%.3f"), n($7, "%+d"),
                 $8, n($9, "%.2f"), n($10, "%.3f"), n($11, "%+d"), n($12, "%.3f") }'
}

usage() { sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }

# ---------------------------------------------------------------------------- main

SYMBOL='$SPX'; MINDAYS=7; MAXDAYS=800; MINWIDTH=500; STRIKES=50; CSV=0; SAVE=""; NO_TSY=0
FILES=()
TSY_DATE=""

[[ "${1:-}" == "login" ]] && { cmd_login; exit 0; }
[[ "${1:-}" == "help" || "${1:-}" == "--help" ]] && usage

while getopts "s:m:d:w:n:cS:f:Th" opt; do
    case "$opt" in
        s) SYMBOL="$OPTARG" ;; m) MINDAYS="$OPTARG" ;; d) MAXDAYS="$OPTARG" ;;
        w) MINWIDTH="$OPTARG" ;; n) STRIKES="$OPTARG" ;; c) CSV=1 ;; S) SAVE="$OPTARG" ;;
        f) FILES+=("$OPTARG") ;; T) NO_TSY=1 ;; h) usage ;; *) usage 1 ;;
    esac
done

TODAY="$(TZ=America/New_York date +%F)"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

if (( ${#FILES[@]} == 0 )); then
    TOKEN="$(access_token)"
    # Small date windows keep each response a manageable size.
    start=0; i=0
    while (( start <= MAXDAYS )); do
        if (( start < 60 )); then step=14; elif (( start < 400 )); then step=45; else step=180; fi
        end=$(( start + step - 1 )); (( end > MAXDAYS )) && end=$MAXDAYS
        if (( end >= MINDAYS )); then
            from="$(TZ=America/New_York date -d "$TODAY + $start days" +%F)"
            to="$(TZ=America/New_York date -d "$TODAY + $end days" +%F)"
            note "fetching $SYMBOL $from … $to"
            fetch_chain "$TOKEN" "$from" "$to" "$WORK/chain-$(printf %03d $i).json"
            i=$(( i + 1 ))
        fi
        start=$(( end + 1 ))
    done
    FILES=("$WORK"/chain-*.json)
    if [[ -n "$SAVE" ]]; then mkdir -p "$SAVE"; cp "${FILES[@]}" "$SAVE/"; note "raw responses saved to $SAVE"; fi
fi

CURVE='[]'
if [[ -n "${BOXQ_CURVE_JSON:-}" ]]; then         # offline/testing: '[[30,4.0],[365,3.7]]'
    CURVE="$BOXQ_CURVE_JSON"; TSY_DATE="(given)"
elif (( ! NO_TSY )); then
    if TSY_OUT="$(treasury_curve)" && [[ -n "$TSY_OUT" ]]; then
        TSY_DATE="${TSY_OUT%%$'\n'*}"
        CURVE="${TSY_OUT#*$'\n'}"
    else
        note "warning: Treasury curve unavailable; comparison columns will be blank"
    fi
fi

ROWS="$(jq -s -c --arg today "$TODAY" --argjson curve "$CURVE" \
    --argjson mindays "$MINDAYS" --argjson maxdays "$MAXDAYS" --argjson minwidth "$MINWIDTH" \
    "$ANALYZE" "${FILES[@]}")"

if (( CSV )); then
    jq -r '(["expiry","days","root","strikes","long_lo","long_hi","long_price","long_yield","lend_spread_bp",
             "short_lo","short_hi","short_price","short_rate","borrow_spread_bp","treasury"]) as $h
           | $h, (.[] | [.[$h[]]]) | @csv' <<<"$ROWS"
    exit 0
fi

UNDER="$(jq -s -r '[.[].underlyingPrice // empty][0] // empty' "${FILES[@]}")"
echo "$SYMBOL ${UNDER:+@ $UNDER }· $(TZ=America/New_York date '+%Y-%m-%d %H:%M ET') · min width $MINWIDTH" \
     "${TSY_DATE:+· Treasury curve $TSY_DATE}"
echo
if [[ "$(jq length <<<"$ROWS")" == 0 ]]; then
    echo "No boxes found (market closed, or try a smaller -w)."
else
    print_table <<<"$ROWS"
fi
cat <<'EOF'

LONG box  = buy +C(lo) -C(hi) +P(hi) -P(lo). You pay COST now and receive (hi-lo) x $100 at expiry: you lend.
SHORT box = sell the same box. You receive CREDIT now and pay (hi-lo) x $100 at expiry: you borrow.
Prices are per share at natural (leg bid/ask) prices: the worst case. Enter one 4-leg limit order and work
it toward the mid. Commissions/fees are not included. European, cash-settled index options only.
EOF
