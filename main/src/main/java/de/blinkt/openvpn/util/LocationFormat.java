/*
 * Copyright (c) 2012-2025 Arne Schwabe
 * Distributed under the GNU GPL v2 with additional terms. For full terms see the file doc/LICENSE.txt
 */

package de.blinkt.openvpn.util;

import androidx.annotation.Nullable;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class LocationFormat {

    private LocationFormat() {}

    // Heuristics: look for city/country substrings in the server label.
    // Keys are lowercase substrings -> values are ISO-2 (for countries) or pretty city names.
    private static final LinkedHashMap<String, String> COUNTRY_MATCH = new LinkedHashMap<>();
    private static final LinkedHashMap<String, String> CITY_MATCH = new LinkedHashMap<>();

    static {
        // ----- Countries (substring -> ISO2) -----
        COUNTRY_MATCH.put("united kingdom", "GB");
        COUNTRY_MATCH.put("britain", "GB");
        COUNTRY_MATCH.put(" uk", "GB");
        COUNTRY_MATCH.put("uk ", "GB");

        COUNTRY_MATCH.put("united states", "US");
        COUNTRY_MATCH.put(" usa", "US");
        COUNTRY_MATCH.put("us-", "US");
        COUNTRY_MATCH.put(" us", "US");

        COUNTRY_MATCH.put("germany", "DE");
        COUNTRY_MATCH.put("spain", "ES");
        COUNTRY_MATCH.put("france", "FR");
        COUNTRY_MATCH.put("netherlands", "NL");
        COUNTRY_MATCH.put("italy", "IT");
        COUNTRY_MATCH.put("canada", "CA");
        COUNTRY_MATCH.put("australia", "AU");
        COUNTRY_MATCH.put("japan", "JP");
        COUNTRY_MATCH.put("singapore", "SG");
        COUNTRY_MATCH.put("ireland", "IE");
        COUNTRY_MATCH.put("switzerland", "CH");
        COUNTRY_MATCH.put("sweden", "SE");
        COUNTRY_MATCH.put("norway", "NO");
        COUNTRY_MATCH.put("denmark", "DK");
        COUNTRY_MATCH.put("poland", "PL");
        COUNTRY_MATCH.put("romania", "RO");
        COUNTRY_MATCH.put("turkey", "TR");
        COUNTRY_MATCH.put("portugal", "PT");
        COUNTRY_MATCH.put("belgium", "BE");
        COUNTRY_MATCH.put("austria", "AT");
        COUNTRY_MATCH.put("czech", "CZ");
        COUNTRY_MATCH.put("hungary", "HU");
        COUNTRY_MATCH.put("finland", "FI");
        COUNTRY_MATCH.put("iceland", "IS");
        COUNTRY_MATCH.put("mexico", "MX");
        COUNTRY_MATCH.put("brazil", "BR");
        COUNTRY_MATCH.put("argentina", "AR");
        COUNTRY_MATCH.put("south africa", "ZA");
        COUNTRY_MATCH.put("hong kong", "HK");
        COUNTRY_MATCH.put("korea", "KR");
        COUNTRY_MATCH.put("uae", "AE");
        COUNTRY_MATCH.put("russia", "RU");

        // ----- Cities (substring -> Pretty Name) -----
        // UK
        CITY_MATCH.put("london", "London");
        CITY_MATCH.put("manchester", "Manchester");
        CITY_MATCH.put("glasgow", "Glasgow");

        // US
        CITY_MATCH.put("new york", "New York");
        CITY_MATCH.put("los angeles", "Los Angeles");
        CITY_MATCH.put("miami", "Miami");
        CITY_MATCH.put("dallas", "Dallas");
        CITY_MATCH.put("chicago", "Chicago");
        CITY_MATCH.put("seattle", "Seattle");
        CITY_MATCH.put("san francisco", "San Francisco");
        CITY_MATCH.put("atlanta", "Atlanta");

        // DE
        CITY_MATCH.put("frankfurt", "Frankfurt");
        CITY_MATCH.put("berlin", "Berlin");
        CITY_MATCH.put("munich", "Munich");
        CITY_MATCH.put("hamburg", "Hamburg");

        // ES
        CITY_MATCH.put("madrid", "Madrid");
        CITY_MATCH.put("barcelona", "Barcelona");
        CITY_MATCH.put("valencia", "Valencia");

        // FR / NL / IT
        CITY_MATCH.put("paris", "Paris");
        CITY_MATCH.put("amsterdam", "Amsterdam");
        CITY_MATCH.put("milan", "Milan");
        CITY_MATCH.put("rome", "Rome");

        // CA / AU / JP / SG
        CITY_MATCH.put("toronto", "Toronto");
        CITY_MATCH.put("vancouver", "Vancouver");
        CITY_MATCH.put("sydney", "Sydney");
        CITY_MATCH.put("melbourne", "Melbourne");
        CITY_MATCH.put("tokyo", "Tokyo");
        CITY_MATCH.put("singapore", "Singapore");

        // Others EU
        CITY_MATCH.put("dublin", "Dublin");
        CITY_MATCH.put("zurich", "Zurich");
        CITY_MATCH.put("stockholm", "Stockholm");
        CITY_MATCH.put("oslo", "Oslo");
        CITY_MATCH.put("copenhagen", "Copenhagen");
        CITY_MATCH.put("warsaw", "Warsaw");
        CITY_MATCH.put("bucharest", "Bucharest");
        CITY_MATCH.put("istanbul", "Istanbul");
        CITY_MATCH.put("lisbon", "Lisbon");
        CITY_MATCH.put("brussels", "Brussels");
        CITY_MATCH.put("vienna", "Vienna");
        CITY_MATCH.put("prague", "Prague");
        CITY_MATCH.put("budapest", "Budapest");
        CITY_MATCH.put("helsinki", "Helsinki");

        // Americas / RoW
        CITY_MATCH.put("mexico city", "Mexico City");
        CITY_MATCH.put("buenos aires", "Buenos Aires");
        CITY_MATCH.put("sao paulo", "São Paulo");
        CITY_MATCH.put("são paulo", "São Paulo");
        CITY_MATCH.put("rio de janeiro", "Rio de Janeiro");
        CITY_MATCH.put("johannesburg", "Johannesburg");
        CITY_MATCH.put("hong kong", "Hong Kong");
        CITY_MATCH.put("seoul", "Seoul");
        CITY_MATCH.put("dubai", "Dubai");
        CITY_MATCH.put("abu dhabi", "Abu Dhabi");
    }

    // ===== Public API =====

    /** Best-effort: get ISO-2 country code from a free-form server label. */
    public static @Nullable String countryIsoFromLabel(@Nullable String label) {
        if (label == null) return null;
        String l = label.toLowerCase(Locale.US);
        for (Map.Entry<String, String> e : COUNTRY_MATCH.entrySet()) {
            if (l.contains(e.getKey())) return e.getValue();
        }
        return null;
    }

    /** Best-effort: get pretty city name from a free-form server label. */
    public static @Nullable String cityFromLabel(@Nullable String label) {
        if (label == null) return null;
        String l = label.toLowerCase(Locale.US);
        for (Map.Entry<String, String> e : CITY_MATCH.entrySet()) {
            if (l.contains(e.getKey())) return e.getValue();
        }
        return null;
    }

    /** Convert ISO-2 ("GB") to flag emoji ("🇬🇧"). */
    public static String iso2ToFlag(@Nullable String iso2) {
        if (iso2 == null || iso2.length() != 2) return "🏳️";
        int a = Character.toUpperCase(iso2.charAt(0)) - 'A' + 0x1F1E6;
        int b = Character.toUpperCase(iso2.charAt(1)) - 'A' + 0x1F1E6;
        return new String(Character.toChars(a)) + new String(Character.toChars(b));
    }

    /** Convenience: derive a flag from a free-form server label. */
    public static String flagFromLabel(@Nullable String label) {
        return iso2ToFlag(countryIsoFromLabel(label));
    }

    /**
     * Build a subtitle like "DE • Frankfurt" from a server label.
     * If only country is known -> "DE".
     * If only city is known -> "Frankfurt".
     * If nothing is known -> empty string.
     */
    public static String formatSubtitle(@Nullable String serverLabel) {
        String iso = countryIsoFromLabel(serverLabel);
        String city = cityFromLabel(serverLabel);
        if (iso != null && city != null) return iso + " • " + city;
        if (iso != null) return iso;
        if (city != null) return city;
        return "";
    }
}
