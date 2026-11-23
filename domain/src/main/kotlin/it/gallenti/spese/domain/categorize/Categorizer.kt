package it.gallenti.spese.domain.categorize

import it.gallenti.spese.domain.model.Group
import it.gallenti.spese.domain.model.RawTransaction
import it.gallenti.spese.domain.model.Source

/**
 * Assegna gruppo e categoria con regole su testo, causale e codice MCC.
 * Le regole sono in ordine: vince la prima che corrisponde.
 */
object Categorizer {

    private data class Rule(
        val group: Group,
        val category: String,
        val keywords: List<String> = emptyList(),
        val mcc: Set<String> = emptySet(),
        val exclude: List<String> = emptyList(),
    )

    private val CARD_KINDS = listOf("Prelievi - Pagamenti", "Pagamento Carta", "BANCOMAT PAY")

    // Spese fisse: si riconoscono dal nome di chi incassa.
    private val recurring = listOf(
        Rule(Group.RICORRENTI, "Affitto", listOf("AFFITTO")),
        Rule(Group.RICORRENTI, "Condominio", listOf("CONDOMINIAL~")),
        Rule(Group.RICORRENTI, "Luce e gas", listOf("OCTOPUS", "ENEL ENERGIA", "HERA COMM", "A2A ENERGIA", "EDISON", "SORGENIA", "IREN MERCATO", "ENI PLENITUDE")),
        Rule(Group.RICORRENTI, "Telefono", listOf("WIND TRE", "VODAFONE", "ILIAD", "FASTWEB", "TELECOM", "TIM S.P.A", "HO. MOBILE", "KENA")),
        Rule(Group.RICORRENTI, "Rifiuti (TARI)", listOf("IREN AMBIENTE", "TARI")),
        Rule(Group.RICORRENTI, "Rate e finanziamenti", listOf("SANTANDER", "COFIDIS", "FINDOMESTIC", "AGOS", "COMPASS", "PAGA IN 3 RATE", "SCALAPAY")),
        Rule(Group.RICORRENTI, "Parcheggio mensile", listOf("APCOA PARCHEGGIO ABBE")),
        Rule(Group.RICORRENTI, "Assicurazione auto", listOf("PRIMA ASSICURAZIONI", "ASSICURAZ~", "UNIPOL", "GENERTEL", "VERTI")),
        Rule(Group.RICORRENTI, "Abbonamenti", listOf("ANTHROPIC", "GOOGLE*GOOGLE*ONE", "GOOGLE ONE", "NETFLIX", "SPOTIFY", "DISNEY", "PRIME VIDEO", "ARUBA", "DAZN", "ICLOUD", "APPLE.COM/BILL"),
            ),
    )

    // Spese variabili, essenziali o voluttuarie.
    private val variable = listOf(
        Rule(Group.RESTO, "Formazione", listOf("COURSERA", "UDEMY", "TUTORIALS DOJO", "LIBRERIA")),
        Rule(Group.RESTO, "Spesa", listOf("LIDL", "ESSELUNGA", "EUROSPIN", "CONAD", "COOP", "PAM", "CARREFOUR", "PENNY", "MD", "IPER~", "SUPERMERCAT~", "SALUMIFICIO~", "MACELLERIA~", "SUPER CONVENIENTE", "TIGOTA", "DESPAR", "PAGAMENTO CARNE"),
            mcc = setOf("5411", "5977")),
        Rule(Group.RESTO, "Carburante", listOf("ENI", "ESSO", "Q8", "TAMOIL", "DISTRIBUTORE", "PETROLI", "CARBURANTE", "BENZINA", "IP", "API-IP", "FULL SERVICE"),
            mcc = setOf("5541", "5542", "5983")),
        Rule(Group.RESTO, "Auto", listOf("AUTOZATTI", "TAGLIANDO", "LACURADELLAUTO", "OFFICINA", "GOMMIST~", "CARROZZ~", "REVISIONE")),
        Rule(Group.RESTO, "Trasporti e pedaggi", listOf("ASPIT", "AUTOSTRAD~", "TRENITALIA", "ITALO", "EASYPARK", "TEP SPA", "APCOA", "PARCHEGGIO", "RIDEMOVI", "TAXI", "MARCONI EXPRESS", "FNM", "TELEPASS~", "SALT", "LAVAGGIO~", "WASH"),
            mcc = setOf("4784", "4111", "4131", "4789", "7523", "7542", "9399"),
            exclude = listOf("ACQUARIO", "VAL DI FIEMME", "PACCOFACILE", "KARERSEE", "ETNA TRASPORTI")),
        Rule(Group.RESTO, "Salute", listOf("FARMACIA~", "OCCHIAL~", "OTTIC~", "MEDIC~", "DENTIST~", "SANITAR~", "OSPEDAL~"), mcc = setOf("5912")),
        Rule(Group.RESTO, "Casa", listOf("BRICO~", "IKEA", "LEROY", "PARMA CLIMA", "IRENYOU", "MONDO CONVENIENZA"),
            mcc = setOf("5311", "5712", "4900", "7622"), exclude = listOf("FOOD KIOSK")),
        Rule(Group.RESTO, "Prelievi contanti", listOf("PRELIEVO BANCOMAT", "PRELIEVO ATM"), mcc = setOf("6011")),
        Rule(Group.RESTO, "Cura persona", listOf("HAIR", "PARRUCCH~", "BARBIER~", "ESTETIC~"), mcc = setOf("7230")),
        Rule(Group.RESTO, "Tasse e PA", listOf("PAGOPA", "F24", "AGENZIA ENTRATE", "BOLLO AUTO"), mcc = setOf("9311")),
        Rule(Group.RESTO, "Spese bancarie", listOf("COMMISS~", "CANONE", "IMPOSTA DI BOLLO", "SPESE TENUTA")),
        Rule(Group.EXTRA, "Ristoranti, bar e delivery",
            listOf("RISTORANT~", "PIZZ~", "TRATTORIA~", "OSTERIA~", "TAVERNA~", "BAR", "CAFF~", "CAFE", "PASTICCERIA~", "GELAT~", "MCDONALD~", "MC DONALD", "KFC", "BURGER", "SUSHI~", "KEBAB~", "CIRCOLO", "PUB", "BIRRERIA~", "ENOTECA", "JUST EAT", "JUSTEAT~", "GLOVO~", "DELIVEROO~", "COFFEE", "FOOD", "CUCINA", "COCKTAIL", "PANIFICIO", "FOCACCERIA", "SPUMANTERIA", "DRINK"),
            mcc = setOf("5812", "5813", "5814", "5811", "5462", "5499", "5734", "5441", "5199", "5993")),
        Rule(Group.EXTRA, "Viaggi e tempo libero",
            listOf("RYANAIR", "WIZZ", "EASYJET", "ITA AIRWAYS", "AIRBNB", "BOOKING", "HOTEL~", "TICKETONE", "CINEMA~", "MULTICINEMA~", "ACQUARIO~", "MUSEO", "ACQUAPARK", "SKI", "VAL DI FIEMME", "KARERSEE", "ETNA TRASPORTI", "DUEGGI", "CASA VACANZE", "VACANZ~", "BARCA", "PRIVE", "WONDERBOX", "AEROPORTO~", "KART", "BIGLIETTI", "OMBRELLON~", "LIDO", "LUNA PARK", "PARCO GIOCHI"),
            mcc = setOf("3301", "4582", "7011", "5941", "7999", "7941", "7832", "7922", "5533", "7997", "5945", "8699")),
        Rule(Group.EXTRA, "Scommesse", listOf("SNAI", "BET365", "SISAL", "LOTTOMATICA", "GOLDBET", "EUROBET"), mcc = setOf("7995")),
        Rule(Group.EXTRA, "Regali", listOf("REGAL~", "AUGURI")),
        Rule(Group.EXTRA, "Videogiochi e digitale", listOf("ENEBA", "G2A", "KINGUIN", "STEAM", "VALVE", "PLAYSTATION", "XBOX", "NINTENDO", "INSTANT GAMING"), mcc = setOf("5816")),
        Rule(Group.EXTRA, "Shopping e acquisti online",
            listOf("AMAZON", "AMZN", "PAYPAL", "VINTED", "TEMU", "ALIEXPRESS", "ALIPAY", "TIKTOK", "TTS SELLER", "UNIEURO", "MEDIAWORLD", "ZALANDO", "SHEIN", "JACK JONES", "ZARA", "DECATHLON", "TOPPS", "PACCOFACILE", "SARTORIA", "PRENATAL", "TGTG", "DAVID INTERNATIONAL", "RATI KFT", "GOOGLE PLAY"),
            mcc = setOf("5651", "5691", "5999", "5732", "5399", "5931", "6012", "6540", "8999", "6300", "7399", "7221", "5992")),
    )

    fun categorize(tx: RawTransaction): Pair<Group, String> {
        tx.forced?.let { return it }
        val text = " ${tx.name} ${tx.description} ".uppercase()
        val kind = tx.kind

        if (kind == "Vincoli" || "VINCOLO N." in text) return Group.INTERNI to "Deposito vincolato (risparmio)"
        if (kind.equals("Giroconto", true) || "GIROCONTO" in text || "PIANO ACCANTONAMENTO" in text) {
            return Group.INTERNI to "Giroconti e risparmi"
        }
        if (tx.amountCents > 0) {
            if (kind == "Stipendi - Pensioni" || kind.contains("Stipendio", true) || "STIPENDIO" in text || "EMOLUMENTI" in text) {
                return Group.ENTRATE to "Stipendio"
            }
            if ("INTERESSI" in text) return Group.ENTRATE to "Interessi"
        }
        recurring.firstOrNull { it.matches(text, tx.mcc) }?.let { return it.group to it.category }

        val isCardRefund = tx.amountCents > 0 && (kind in CARD_KINDS || tx.source == Source.PAYPAL && kind == "Rimborso di pagamento")
        if (tx.amountCents > 0 && !isCardRefund) return Group.ENTRATE to "Rimborsi e altre entrate"

        variable.firstOrNull { it.matches(text, tx.mcc) }?.let { return it.group to it.category }
        return Group.EXTRA to "Altro"
    }

    private fun Rule.matches(text: String, mcc: String?): Boolean {
        if (exclude.any { it in text }) return false
        return (mcc != null && mcc in this.mcc) || keywords.any { keywordRegex(it).containsMatchIn(text) }
    }

    private val regexCache = java.util.concurrent.ConcurrentHashMap<String, Regex>()

    /**
     * Parola o frase intera ("ESSO" non trova "PRESSO", "AGOS" non trova "AGOSTO").
     * Una parola che finisce con ~ è una radice: "PIZZ~" trova "PIZZERIA".
     */
    internal fun keywordRegex(keyword: String): Regex = regexCache.getOrPut(keyword) {
        val stem = keyword.endsWith("~")
        val body = Regex.escape(keyword.removeSuffix("~"))
        Regex("(?<![A-Z0-9])" + body + if (stem) "" else "(?![A-Z0-9])")
    }
}
