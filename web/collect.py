#!/usr/bin/env python3
"""Collecte pour la page web Ecowidget (stdlib seulement).

Chaque exécution (toutes les 30 min, voir .github/workflows/web.yml) :
  1. mesures de la station (cloud Ecowitt) -> station.csv, une ligne par heure, mise à jour sans doublon ;
  2. prévisions de chaque modèle (Open-Meteo) -> latest.json, lu par la page ;
  3. prévisions « archivées » pour juger les modèles -> forecasts.csv :
       - échéances courtes (+1 à +12 h) des runs de 0, 6, 12 et 18 h UTC (API Single Runs) ;
       - échéances J+1 à J+5 (API Previous Runs).
Les fichiers de données s'incrémentent : rien n'est jamais supprimé. Variables d'environnement :
ECOWITT_APP_KEY, ECOWITT_API_KEY, ECOWITT_MAC (obligatoires pour les mesures) ; LAT, LON, ELEVATION (facultatifs :
position et altitude lues dans le compte Ecowitt / 685 m par défaut) ; TZ_NAME (Europe/Paris par défaut).
"""
import argparse
import csv
import json
import os
import shutil
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone

HOUR = 3600
STATION_COLS = ["epoch", "ts_utc", "temp", "feels", "hum", "dew", "press", "wind", "gust", "wind_dir",
                "rain_h", "rain_day", "uv", "solar", "temp_in", "hum_in", "temp_min", "temp_max"]
FC_COLS = ["epoch", "run", "lead_h", "ech", "model", "temp", "rain"]

# id Open-Meteo, nom court, libellé, jusqu'à quel jour on archive (J+1…), échéances courtes archivées
MODELS = [
    ("meteofrance_arome_france_hd", "AROME", "AROME HD", 2, True),
    ("icon_d2", "ICON-D2", "ICON-D2", 2, True),
    ("meteoswiss_icon_ch1", "ICON-CH1", "ICON-CH1", 0, True),
    ("icon_eu", "ICON-EU", "ICON-EU", 5, False),
    ("meteofrance_arpege_europe", "ARPEGE", "ARPEGE Europe", 4, False),
    ("ecmwf_ifs025", "IFS", "ECMWF IFS", 5, False),
    ("best_match", "Auto", "Auto Open-Meteo", 5, False),
]
BUCKETS = [("h1", [1, 2]), ("h3", [3, 4]), ("h5", [5, 6]), ("h11", [11, 12])]
RUN_HOURS = [0, 6, 12, 18]
MAX_LEAD_H = 12
FORECAST_VARS = ["temperature_2m", "precipitation", "wind_speed_10m", "wind_gusts_10m", "cloud_cover", "relative_humidity_2m"]
SHORT = {"temperature_2m": "temp", "precipitation": "rain", "wind_speed_10m": "wind", "wind_gusts_10m": "gust",
         "cloud_cover": "cloud", "relative_humidity_2m": "hum"}

errors = []


def log(msg):
    print(msg, flush=True)


def http_json(url, timeout=40, tries=3):
    """GET JSON avec quelques nouvelles tentatives (429 / 5xx). Ne journalise jamais l'URL (clés dans l'URL)."""
    last = None
    for k in range(tries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "ecowidget-web/1.0", "Accept": "application/json"})
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return json.loads(r.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            last = "HTTP %s" % e.code
            if e.code not in (429, 500, 502, 503, 504):
                break
        except Exception as e:  # réseau, JSON
            last = type(e).__name__
        time.sleep(4 * (k + 1))
    raise RuntimeError(last or "erreur réseau")


# ───────────────────────────── Ecowitt ─────────────────────────────

def ecowitt(path, params):
    q = {"application_key": os.environ["ECOWITT_APP_KEY"].strip(), "api_key": os.environ["ECOWITT_API_KEY"].strip()}
    if path != "api/v3/device/list":
        q["mac"] = os.environ["ECOWITT_MAC"].strip().upper().replace("-", ":")
    q.update(params)
    d = http_json("https://api.ecowitt.net/" + path + "?" + urllib.parse.urlencode(q))
    if d.get("code") != 0:
        raise RuntimeError("Ecowitt code %s : %s" % (d.get("code"), d.get("msg")))
    data = d.get("data")
    if not data:
        raise RuntimeError("Ecowitt : aucune donnée")
    return data


def num(v):
    try:
        x = float(str(v).strip())
        return x if x == x else None
    except (TypeError, ValueError):
        return None


def series(data, group, key):
    lst = ((data.get(group) or {}).get(key) or {}).get("list") or {}
    out = []
    for k, v in lst.items():
        t = num(k)
        x = num(v)
        if t is not None and x is not None:
            out.append((int(t), x))
    out.sort()
    return out


def nearest(s, t, tol):
    best, bd = None, None
    for ts, v in s:
        d = abs(ts - t)
        if bd is None or d < bd:
            best, bd = v, d
    return best if bd is not None and bd <= tol else None


def window_max(s, t0, t1):
    vals = [v for ts, v in s if t0 < ts <= t1]
    return max(vals) if vals else None


def window_mean(s, t0, t1):
    vals = [v for ts, v in s if t0 < ts <= t1]
    return sum(vals) / len(vals) if vals else None


def station_history(now, tzname):
    """Mesures horaires des ~3 derniers jours, dérivées de l'historique 30 min du cloud Ecowitt."""
    from zoneinfo import ZoneInfo
    z = ZoneInfo(tzname)
    fmt = "%Y-%m-%d %H:%M:%S"
    start = datetime.fromtimestamp(now - 74 * HOUR, z).strftime(fmt)
    end = datetime.fromtimestamp(now + 2 * HOUR, z).strftime(fmt)
    groups_try = [
        "outdoor,pressure,wind,rainfall,rainfall_piezo,indoor,solar_and_uvi",
        "outdoor,pressure,wind,rainfall,indoor",
        "outdoor,pressure,wind,rainfall_piezo,indoor",
        "outdoor,rainfall,rainfall_piezo",
        "outdoor",
    ]
    data, last = None, None
    for g in groups_try:
        try:
            data = ecowitt("api/v3/device/history", {
                "start_date": start, "end_date": end, "cycle_type": "30min", "call_back": g,
                "temp_unitid": "1", "pressure_unitid": "3", "wind_speed_unitid": "7", "rainfall_unitid": "12",
                "solar_irradiance_unitid": "16"})
            break
        except Exception as e:
            last = e
    if data is None:
        raise last or RuntimeError("historique indisponible")
    temp = series(data, "outdoor", "temperature")
    if not temp:
        raise RuntimeError("aucune température dans l'historique")
    S = {
        "feels": series(data, "outdoor", "feels_like"), "hum": series(data, "outdoor", "humidity"),
        "dew": series(data, "outdoor", "dew_point"), "press": series(data, "pressure", "relative"),
        "wind": series(data, "wind", "wind_speed"), "gust": series(data, "wind", "wind_gust"),
        "dir": series(data, "wind", "wind_direction"), "uv": series(data, "solar_and_uvi", "uvi"),
        "solar": series(data, "solar_and_uvi", "solar"), "temp_in": series(data, "indoor", "temperature"),
        "hum_in": series(data, "indoor", "humidity"),
    }
    rain_series = [series(data, g, "daily") for g in ("rainfall", "rainfall_piezo")]
    rain_series = [s for s in rain_series if len(s) >= 4]
    rain = max(rain_series, key=lambda s: max(v for _, v in s)) if rain_series else None
    rows = []
    first = (now - 72 * HOUR) // HOUR + 1
    last_h = now // HOUR
    for hr in range(first, last_h + 1):
        t = hr * HOUR
        r = {"epoch": t}
        r["temp"] = nearest(temp, t, 900)
        if r["temp"] is None:
            continue
        for k in ("feels", "hum", "dew", "press", "temp_in", "hum_in", "uv", "solar"):
            r[k] = nearest(S[k], t, 900)
        r["wind"] = window_mean(S["wind"], t - HOUR, t) if S["wind"] else None
        r["gust"] = window_max(S["gust"], t - HOUR, t) if S["gust"] else None
        r["wind_dir"] = nearest(S["dir"], t, 900)
        tw = [v for ts, v in temp if t - HOUR < ts <= t]
        if tw:
            r["temp_min"], r["temp_max"] = min(tw), max(tw)
        if rain:
            a, b = nearest(rain, t, 600), nearest(rain, t - HOUR, 600)
            if a is not None:
                r["rain_day"] = a
                if b is not None:
                    d = a - b
                    r["rain_h"] = a if d < -0.05 else max(d, 0.0)
        rows.append(r)
    return rows


def station_now():
    """Dernières mesures en direct : valeurs principales + toutes les mesures reçues."""
    data = ecowitt("api/v3/device/real_time", {
        "call_back": "all", "temp_unitid": "1", "pressure_unitid": "3", "wind_speed_unitid": "7",
        "rainfall_unitid": "12", "solar_irradiance_unitid": "16"})

    def leaf(*path):
        cur = data
        for p in path:
            cur = (cur or {}).get(p) if isinstance(cur, dict) else None
        return cur if isinstance(cur, dict) else None

    def val(*path):
        lf = leaf(*path)
        return num(lf.get("value")) if lf else None

    def tim(*path):
        lf = leaf(*path)
        return int(num(lf.get("time")) or 0) if lf else 0

    rain_rate = val("rainfall", "rain_rate")
    if rain_rate is None:
        rain_rate = val("rainfall_piezo", "rain_rate")
    rain_day = val("rainfall", "daily")
    if rain_day is None:
        rain_day = val("rainfall_piezo", "daily")
    snap = {
        "observed": tim("outdoor", "temperature") or tim("indoor", "temperature"),
        "temp": val("outdoor", "temperature"), "feels": val("outdoor", "feels_like"), "hum": val("outdoor", "humidity"),
        "dew": val("outdoor", "dew_point"), "temp_in": val("indoor", "temperature"), "hum_in": val("indoor", "humidity"),
        "press": val("pressure", "relative"), "press_abs": val("pressure", "absolute"),
        "wind": val("wind", "wind_speed"), "gust": val("wind", "wind_gust"), "wind_dir": val("wind", "wind_direction"),
        "rain_rate": rain_rate, "rain_day": rain_day, "uv": val("solar_and_uvi", "uvi"), "solar": val("solar_and_uvi", "solar"),
    }
    raw = {}
    for g, items in data.items():
        if not isinstance(items, dict):
            continue
        for k, lf in items.items():
            if isinstance(lf, dict) and "value" in lf:
                raw["%s.%s" % (g, k)] = [lf.get("value"), lf.get("unit", "")]
    return snap, raw


def station_location():
    """Position et nom de la station depuis le compte Ecowitt (si LAT / LON ne sont pas fournis)."""
    data = ecowitt("api/v3/device/list", {})
    mac = os.environ["ECOWITT_MAC"].strip().upper().replace("-", ":")
    for d in data.get("list") or []:
        if str(d.get("mac", "")).upper().replace("-", ":") == mac:
            lat, lon = num(d.get("latitude")), num(d.get("longitude"))
            if lat is not None and lon is not None and not (lat == 0 and lon == 0):
                return lat, lon
    return None


def station_today(now, tzname):
    """Mesures de la journée en cours, pas de 5 min (historique Ecowitt), pour les courbes de la page."""
    from zoneinfo import ZoneInfo
    z = ZoneInfo(tzname)
    fmt = "%Y-%m-%d %H:%M:%S"
    mid = datetime.fromtimestamp(now, z).replace(hour=0, minute=0, second=0, microsecond=0)
    start = mid.strftime(fmt)
    end = datetime.fromtimestamp(now + 600, z).strftime(fmt)
    data, last = None, None
    for g in ("outdoor,pressure,wind,rainfall,rainfall_piezo,solar_and_uvi", "outdoor,pressure,wind,rainfall_piezo,solar_and_uvi", "outdoor,pressure,wind", "outdoor"):
        try:
            data = ecowitt("api/v3/device/history", {
                "start_date": start, "end_date": end, "cycle_type": "5min", "call_back": g,
                "temp_unitid": "1", "pressure_unitid": "3", "wind_speed_unitid": "7", "rainfall_unitid": "12",
                "solar_irradiance_unitid": "16"})
            break
        except Exception as e:
            last = e
    if data is None:
        raise last or RuntimeError("historique 5 min indisponible")
    temp = series(data, "outdoor", "temperature")
    if not temp:
        raise RuntimeError("aucune température sur la journée")
    rain = None
    for g in ("rainfall_piezo", "rainfall"):
        s = series(data, g, "daily")
        if s and (rain is None or max(v for _, v in s) > max(v for _, v in rain)):
            rain = s
    cols = {
        "temp": temp, "feels": series(data, "outdoor", "feels_like"), "hum": series(data, "outdoor", "humidity"),
        "dew": series(data, "outdoor", "dew_point"), "press": series(data, "pressure", "relative"),
        "wind": series(data, "wind", "wind_speed"), "gust": series(data, "wind", "wind_gust"),
        "dir": series(data, "wind", "wind_direction"), "uv": series(data, "solar_and_uvi", "uvi"),
        "solar": series(data, "solar_and_uvi", "solar"), "rain_day": rain or [],
    }
    ts = [t for t, _ in temp]
    out = {"midnight": int(mid.timestamp()), "t": ts}
    for k, sr in cols.items():
        d = dict(sr)
        out[k] = [None if d.get(t) is None else round(d[t], 2) for t in ts]
    return out


OM_CURRENT = ["temperature_2m", "apparent_temperature", "relative_humidity_2m", "precipitation", "weather_code", "cloud_cover",
              "pressure_msl", "surface_pressure", "wind_speed_10m", "wind_direction_10m", "wind_gusts_10m", "is_day"]
OM_HOURLY = ["temperature_2m", "apparent_temperature", "dew_point_2m", "relative_humidity_2m", "precipitation", "precipitation_probability",
             "weather_code", "pressure_msl", "wind_speed_10m", "wind_gusts_10m", "wind_direction_10m", "cloud_cover", "cloud_cover_low",
             "cloud_cover_mid", "cloud_cover_high", "visibility", "uv_index", "shortwave_radiation", "freezing_level_height", "snowfall",
             "cape", "is_day", "sunshine_duration"]
OM_DAILY = ["weather_code", "temperature_2m_max", "temperature_2m_min", "apparent_temperature_max", "apparent_temperature_min",
            "sunrise", "sunset", "daylight_duration", "sunshine_duration", "uv_index_max", "precipitation_sum", "rain_sum", "snowfall_sum",
            "precipitation_hours", "precipitation_probability_max", "wind_speed_10m_max", "wind_gusts_10m_max", "wind_direction_10m_dominant",
            "shortwave_radiation_sum"]


def rnd(a, n=2):
    return [None if x is None else round(x, n) for x in a]


def open_meteo_full(loc):
    """Prévision détaillée « Auto » (conditions actuelles, horaire sur 7 jours, quotidien) : tout ce que l'API sait donner."""
    p = loc.params(True)
    p.update({"current": ",".join(OM_CURRENT), "hourly": ",".join(OM_HOURLY), "daily": ",".join(OM_DAILY),
              "forecast_days": "7", "past_days": "1", "models": "best_match"})
    d = om("api.open-meteo.com", "v1/forecast", p)
    out = {"elevation": d.get("elevation"), "utc_offset": d.get("utc_offset_seconds"), "current": d.get("current") or {},
           "hourly": {}, "daily": {}}
    h = d.get("hourly") or {}
    out["hourly"]["time"] = h.get("time") or []
    for k in OM_HOURLY:
        a = h.get(k)
        if isinstance(a, list):
            out["hourly"][k] = rnd(a)
    dl = d.get("daily") or {}
    out["daily"]["time"] = dl.get("time") or []
    for k in OM_DAILY:
        a = dl.get(k)
        if isinstance(a, list):
            out["daily"][k] = a if k in ("sunrise", "sunset") else rnd(a)
    return out


AIR_CURRENT = ["european_aqi", "pm10", "pm2_5", "ozone", "nitrogen_dioxide", "sulphur_dioxide", "carbon_monoxide", "dust", "uv_index",
               "alder_pollen", "birch_pollen", "grass_pollen", "mugwort_pollen", "olive_pollen", "ragweed_pollen"]
AIR_HOURLY = ["european_aqi", "pm10", "pm2_5", "ozone", "alder_pollen", "birch_pollen", "grass_pollen", "mugwort_pollen", "ragweed_pollen"]


def air_quality(loc):
    p = loc.params(False)
    p.update({"current": ",".join(AIR_CURRENT), "hourly": ",".join(AIR_HOURLY), "forecast_days": "3", "past_days": "1"})
    d = om("air-quality-api.open-meteo.com", "v1/air-quality", p)
    h = d.get("hourly") or {}
    out = {"current": d.get("current") or {}, "hourly": {"time": h.get("time") or []}}
    for k in AIR_HOURLY:
        a = h.get(k)
        if isinstance(a, list) and any(x is not None for x in a):
            out["hourly"][k] = rnd(a, 1)
    return out


# ───────────────────────────── CSV ─────────────────────────────

def read_csv(path, cols):
    rows = {}
    if not os.path.exists(path):
        return rows
    with open(path, newline="", encoding="utf-8") as f:
        for r in csv.DictReader(f):
            rows[key_of(r, cols)] = r
    return rows


def key_of(r, cols):
    if cols is STATION_COLS:
        return r["epoch"]
    return (r["epoch"], r.get("run", ""), r["lead_h"], r["model"])


def write_csv(path, cols, rows):
    tmp = path + ".tmp"
    with open(tmp, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=cols, extrasaction="ignore", lineterminator="\n")
        w.writeheader()
        for k in sorted(rows, key=lambda k: (int(rows[k]["epoch"]), str(rows[k].get("model", "")), int(rows[k].get("lead_h") or 0))):
            w.writerow(rows[k])
    os.replace(tmp, path)


def fmt(v, n=2):
    if v is None:
        return ""
    s = ("%." + str(n) + "f") % v
    return s.rstrip("0").rstrip(".") if "." in s else s


def upsert_station(path, new_rows):
    rows = read_csv(path, STATION_COLS)
    changed = 0
    for r in new_rows:
        e = str(r["epoch"])
        old = rows.get(e, {})
        row = {"epoch": e, "ts_utc": datetime.fromtimestamp(r["epoch"], timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")}
        for c in STATION_COLS[2:]:
            src = "wind_dir" if c == "wind_dir" else c
            v = r.get(src)
            row[c] = fmt(v, 2) if v is not None else old.get(c, "")
        if old != row:
            changed += 1
        rows[e] = row
    write_csv(path, STATION_COLS, rows)
    return changed


def upsert_forecasts(path, new_rows):
    rows = read_csv(path, FC_COLS)
    added = 0
    for r in new_rows:
        k = (str(r["epoch"]), str(r.get("run", "")), str(r["lead_h"]), r["model"])
        row = {"epoch": str(r["epoch"]), "run": str(r.get("run", "")), "lead_h": str(r["lead_h"]), "ech": r["ech"],
               "model": r["model"], "temp": fmt(r.get("temp")), "rain": fmt(r.get("rain"))}
        if k not in rows:
            added += 1
        rows[k] = row
    write_csv(path, FC_COLS, rows)
    return added, rows


# ───────────────────────────── Open-Meteo ─────────────────────────────

class Loc:
    def __init__(self, lat, lon, elev):
        self.lat = round(lat, 2)
        self.lon = round(lon, 2)
        self.elev = elev

    def params(self, with_elev=True):
        p = {"latitude": "%.2f" % self.lat, "longitude": "%.2f" % self.lon, "timezone": "auto", "timeformat": "unixtime"}
        if with_elev and self.elev is not None:
            p["elevation"] = "%.0f" % self.elev
        return p


def om(host, path, params):
    return http_json("https://%s/%s?%s" % (host, path, urllib.parse.urlencode(params)), timeout=60)


def col(h, name):
    a = h.get(name)
    return a if isinstance(a, list) else None


def latest_forecast(loc, now):
    """Prévisions horaires de tous les modèles (24 h passées + 6 jours) pour la page."""
    p = loc.params(True)
    p.update({"hourly": ",".join(FORECAST_VARS), "models": ",".join(m[0] for m in MODELS), "forecast_days": "6", "past_days": "1"})
    d = om("api.open-meteo.com", "v1/forecast", p)
    h = d.get("hourly") or {}
    times = h.get("time") or []
    out = {"times": times, "series": {}}
    for mid, short, label, _, _ in MODELS:
        s = {}
        for v in FORECAST_VARS:
            a = col(h, "%s_%s" % (v, mid))
            if a is None and len(MODELS) == 1:
                a = col(h, v)
            if a is not None and any(x is not None for x in a):
                s[SHORT[v]] = [None if x is None else round(x, 2) for x in a]
        if s:
            out["series"][short] = s
    out["elevation"] = d.get("elevation")
    return out


def single_run(loc, mid, run):
    p = loc.params(True)
    p.update({"run": datetime.fromtimestamp(run, timezone.utc).strftime("%Y-%m-%dT%H:%M"),
              "hourly": "temperature_2m,precipitation", "models": mid})
    return om("single-runs-api.open-meteo.com", "v1/forecast", p).get("hourly") or {}


def previous_runs(loc, mid, max_day, past_days):
    p = loc.params(True)
    hv = []
    for n in range(1, max_day + 1):
        hv += ["temperature_2m_previous_day%d" % n, "precipitation_previous_day%d" % n]
    p.update({"past_days": str(past_days), "forecast_days": "1", "hourly": ",".join(hv), "models": mid})
    return om("previous-runs-api.open-meteo.com", "v1/forecast", p).get("hourly") or {}


def collect_short_runs(loc, rows_known, now):
    """Nouveaux runs (0, 6, 12, 18 h UTC) des 3 modèles fins : leurs échéances +1…+12 h."""
    done = {(r["run"], r["model"]) for r in rows_known.values() if r.get("run")}
    new = []
    day0 = (now // 86400) * 86400
    runs = [day0 - back * 86400 + h * HOUR for back in range(0, 3) for h in RUN_HOURS]
    for run in sorted(runs):
        if run + (MAX_LEAD_H + 3) * HOUR > now or run < now - 40 * HOUR:
            continue
        for mid, short, _, _, fine in MODELS:
            if not fine or (str(run), short) in done:
                continue
            try:
                h = single_run(loc, mid, run)
            except Exception as e:
                errors.append("run %s %s : %s" % (datetime.fromtimestamp(run, timezone.utc).strftime("%d/%m %Hh"), short, e))
                continue
            times, temp, rain = h.get("time") or [], col(h, "temperature_2m"), col(h, "precipitation")
            got = 0
            for ech, leads in BUCKETS:
                for lead in leads:
                    t = run + lead * HOUR
                    if t not in times:
                        continue
                    i = times.index(t)
                    tv = temp[i] if temp and i < len(temp) else None
                    rv = rain[i] if rain and i < len(rain) else None
                    if tv is None and rv is None:
                        continue
                    new.append({"epoch": t, "run": run, "lead_h": lead, "ech": ech, "model": short, "temp": tv, "rain": rv})
                    got += 1
            if got:
                done.add((str(run), short))
    return new


def collect_day_leads(loc, now, past_days=3):
    new = []
    for mid, short, _, maxd, _ in MODELS:
        if maxd <= 0:
            continue
        try:
            h = previous_runs(loc, mid, maxd, past_days)
        except Exception as e:
            errors.append("jours %s : %s" % (short, e))
            continue
        times = h.get("time") or []
        for n in range(1, maxd + 1):
            temp, rain = col(h, "temperature_2m_previous_day%d" % n), col(h, "precipitation_previous_day%d" % n)
            for i, t in enumerate(times):
                if t > now - HOUR:
                    continue
                tv = temp[i] if temp and i < len(temp) else None
                rv = rain[i] if rain and i < len(rain) else None
                if tv is None and rv is None:
                    continue
                new.append({"epoch": t, "run": "", "lead_h": 24 * n, "ech": "j%d" % n, "model": short, "temp": tv, "rain": rv})
    return new


# ───────────────────────────── Programme ─────────────────────────────

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", required=True, help="dossier des données (branche data)")
    ap.add_argument("--seed", default=None, help="dossier d'un historique de départ, copié si les fichiers n'existent pas")
    ap.add_argument("--days", action="store_true", help="forcer la collecte des échéances en jours")
    a = ap.parse_args()
    os.makedirs(a.data, exist_ok=True)
    now = int(time.time())
    tzname = os.environ.get("TZ_NAME", "Europe/Paris")
    stp, fcp, jsp, stt = (os.path.join(a.data, n) for n in ("station.csv", "forecasts.csv", "latest.json", "state.json"))
    if a.seed:
        for name in ("station.csv", "forecasts.csv"):
            dst, src = os.path.join(a.data, name), os.path.join(a.seed, name)
            if not os.path.exists(dst) and os.path.exists(src):
                shutil.copy(src, dst)
                log("graine : %s" % name)
    state = {}
    if os.path.exists(stt):
        try:
            state = json.load(open(stt, encoding="utf-8"))
        except Exception:
            state = {}
    has_eco = all(os.environ.get(k) for k in ("ECOWITT_APP_KEY", "ECOWITT_API_KEY", "ECOWITT_MAC"))
    latest = {"generated": now, "errors": [], "station": None, "raw": {}, "forecast": None, "today": None, "om": None, "air": None, "tz": tzname,
              "models": [{"short": m[1], "label": m[2]} for m in MODELS]}
    # Si la collecte échoue, on garde les données précédentes pour ce bloc.
    if os.path.exists(jsp):
        try:
            prev = json.load(open(jsp, encoding="utf-8"))
            for k in ("station", "raw", "forecast", "today", "om", "air"):
                latest[k] = prev.get(k)
        except Exception:
            pass

    # 1) Station
    if has_eco:
        try:
            rows = station_history(now, tzname)
            ch = upsert_station(stp, rows)
            log("station.csv : %d lignes ajoutées ou mises à jour" % ch)
        except Exception as e:
            errors.append("historique station : %s" % e)
        try:
            latest["today"] = station_today(now, tzname)
        except Exception as e:
            errors.append("journée 5 min : %s" % e)
        try:
            snap, raw = station_now()
            latest["station"], latest["raw"] = snap, raw
        except Exception as e:
            errors.append("mesures en direct : %s" % e)
    else:
        errors.append("secrets Ecowitt absents : mesures de la station non collectées")

    # 2) Position
    lat, lon = os.environ.get("LAT"), os.environ.get("LON")
    loc = None
    try:
        if lat and lon:
            loc = (float(lat), float(lon))
        elif has_eco:
            loc = station_location()
    except Exception as e:
        errors.append("position : %s" % e)
    elev = None
    try:
        elev = float(os.environ.get("ELEVATION") or 685)
    except ValueError:
        pass
    if loc is None:
        errors.append("position inconnue : prévisions non collectées (LAT / LON ou compte Ecowitt)")
    else:
        L = Loc(loc[0], loc[1], elev)
        # 3) Prévisions affichées
        try:
            latest["forecast"] = latest_forecast(L, now)
        except Exception as e:
            errors.append("prévisions Open-Meteo : %s" % e)
        try:
            latest["om"] = open_meteo_full(L)
        except Exception as e:
            errors.append("Open-Meteo détaillé : %s" % e)
        try:
            latest["air"] = air_quality(L)
        except Exception as e:
            errors.append("qualité de l'air : %s" % e)
        # 4) Prévisions archivées
        try:
            known = read_csv(fcp, FC_COLS)
            new = collect_short_runs(L, known, now)
            if a.days or now - state.get("days_at", 0) > 5 * HOUR:
                d = collect_day_leads(L, now)
                if d:
                    state["days_at"] = now
                new += d
            added, _ = upsert_forecasts(fcp, new)
            log("forecasts.csv : %d lignes nouvelles" % added)
        except Exception as e:
            errors.append("archives de prévisions : %s" % e)

    latest["errors"] = errors
    latest["generated"] = now
    with open(jsp, "w", encoding="utf-8") as f:
        json.dump(latest, f, ensure_ascii=False, separators=(",", ":"))
    with open(stt, "w", encoding="utf-8") as f:
        json.dump(state, f)
    for e in errors:
        log("! " + e)
    return 0


if __name__ == "__main__":
    sys.exit(main())
