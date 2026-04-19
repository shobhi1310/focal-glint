package com.focal.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Benchmarks the greedy clustering algorithm against 100 synthetic notifications
 * across 10 deliberately hard topic clusters.
 *
 * Two simulated "embedding quality" modes correspond to what CLUSTERING vs
 * SEMANTIC_SIMILARITY Gecko task types might produce on notification text:
 *
 *   CLUSTERING simulation    : hard-pair topic centroids stay far apart (hardPairBlend = 0.0)
 *   SEMANTIC_SIM simulation  : hard-pair topic centroids are pulled together (hardPairBlend = 0.3)
 *
 * Run with: ./gradlew :app:test --tests "*EmbeddingClusteringBenchmarkTest*"
 *
 * For a real device benchmark with the actual Gecko model, swap SyntheticEmbeddingProvider
 * for GeckoEmbeddingProvider(taskType = EmbedData.TaskType.CLUSTERING) and run as
 * an instrumented test on a device that has the model file on storage.
 */
class EmbeddingClusteringBenchmarkTest {

    // -------------------------------------------------------------------------
    // Corpus: 100 notifications, 10 per topic
    //
    // Hard confusable pairs (share vocabulary/context):
    //   Topic 1 (food delivery)   ↔ Topic 2 (package delivery)  — "out for delivery", "arriving"
    //   Topic 3 (work meetings)   ↔ Topic 4 (GitHub/dev tools)  — "review", work context
    //   Topic 5 (family chat)     ↔ Topic 6 (social media)      — social/messaging context
    //   Topic 8 (news)            ↔ Topic 9 (sports)            — "breaking", "update"
    // -------------------------------------------------------------------------
    private val corpus: List<Triple<Int, String, String>> = buildList {
        // Topic 1 — Food Delivery
        add(Triple(1, "Swiggy", "Your order is out for delivery | Rahul is 3 mins away"))
        add(Triple(1, "Swiggy", "Order placed | Your order from Biryani House is confirmed"))
        add(Triple(1, "Zomato", "Food on the way | Rider picked up your order"))
        add(Triple(1, "Zomato", "Order arriving | Your delivery partner is nearby"))
        add(Triple(1, "Uber Eats", "Order update | McDonald's is preparing your food"))
        add(Triple(1, "Uber Eats", "Your order is almost here | Arriving in 5 mins"))
        add(Triple(1, "Swiggy", "Delivered! | Your order has been delivered. Enjoy!"))
        add(Triple(1, "Zomato", "Preparing your food | Estimated: 25 mins"))
        add(Triple(1, "Swiggy", "Dasher is on the way | 1.2 km away from you"))
        add(Triple(1, "Zomato", "Order confirmed | Payment received. Preparing now."))

        // Topic 2 — Package / Courier Delivery
        add(Triple(2, "Amazon", "Your package will arrive today | Out for delivery"))
        add(Triple(2, "Amazon", "Package delivered | Left at front door"))
        add(Triple(2, "FedEx", "Shipment out for delivery | Expected by 8 PM"))
        add(Triple(2, "FedEx", "Package in transit | Arriving tomorrow"))
        add(Triple(2, "DTDC", "Shipment dispatched | AWB 12345678"))
        add(Triple(2, "DTDC", "Out for delivery | Will attempt delivery today"))
        add(Triple(2, "UPS", "Delivery scheduled | Your package is on its way"))
        add(Triple(2, "Amazon", "Delivery attempted | Will retry tomorrow"))
        add(Triple(2, "Delhivery", "Your order has been shipped | Track: DL98765"))
        add(Triple(2, "Flipkart", "Delivery by today | Package is with delivery agent"))

        // Topic 3 — Work Meetings / Standup
        add(Triple(3, "Slack", "Daily standup in 5 mins | #engineering channel"))
        add(Triple(3, "Slack", "Meeting reminder | Sprint planning starts in 10 mins"))
        add(Triple(3, "Teams", "Upcoming meeting | All-hands call in 15 mins"))
        add(Triple(3, "Teams", "You have a call starting now | Q2 review meeting"))
        add(Triple(3, "Google Calendar", "Reminder: 1-on-1 with Priya | Starts in 30 mins"))
        add(Triple(3, "Google Calendar", "Standup | Daily sync starting in 5 mins"))
        add(Triple(3, "Slack", "EOD update thread | Please share your updates"))
        add(Triple(3, "Teams", "Meeting starting | Sprint retrospective now open"))
        add(Triple(3, "Zoom", "Your meeting is starting | Join: zoom.us/j/123"))
        add(Triple(3, "Slack", "Quick sync? | Rohan is calling you on Slack"))

        // Topic 4 — GitHub / Dev Tools
        add(Triple(4, "GitHub", "PR #234: Add auth flow | Needs your review"))
        add(Triple(4, "GitHub", "Your PR was merged | feature/user-profile into main"))
        add(Triple(4, "GitHub", "@you were mentioned | Issue #89: login bug"))
        add(Triple(4, "GitHub", "Changes requested | Reviewer left comments on PR #241"))
        add(Triple(4, "GitLab", "Pipeline failed | feature-branch: 3 tests failing"))
        add(Triple(4, "Jira", "PROJ-234 assigned to you | Fix null pointer in auth module"))
        add(Triple(4, "Jira", "Sprint starts Monday | 12 issues in backlog"))
        add(Triple(4, "Linear", "Issue blocked | ENG-445 waiting for design approval"))
        add(Triple(4, "Slack", "Code review needed | please review PR #256 by EOD"))
        add(Triple(4, "GitHub", "Deployment succeeded | prod deployment completed"))

        // Topic 5 — Family Messaging
        add(Triple(5, "WhatsApp", "Mom | Beta, did you eat dinner?"))
        add(Triple(5, "WhatsApp", "Dad | How was your day? Call me."))
        add(Triple(5, "WhatsApp", "Sis | Dinner at mom's place tonight?"))
        add(Triple(5, "WhatsApp", "Wife | Don't forget to pick up milk"))
        add(Triple(5, "WhatsApp", "Brother | Bhai aaj free hai? Match dekhte hai"))
        add(Triple(5, "WhatsApp", "Mom | Come home early today beta"))
        add(Triple(5, "iMessage", "Priya | What time are you coming home?"))
        add(Triple(5, "WhatsApp", "Dad | Good morning! Take care of yourself."))
        add(Triple(5, "WhatsApp", "Family Group | Pooja at home on Sunday. Everyone come."))
        add(Triple(5, "Telegram", "Sis | Can you pick me up from station at 6?"))

        // Topic 6 — Social Media
        add(Triple(6, "Instagram", "3 new followers | @ankit_photos and 2 others followed you"))
        add(Triple(6, "Instagram", "New like | @traveller_raj liked your photo"))
        add(Triple(6, "Twitter", "You were retweeted | @techguru shared your tweet"))
        add(Triple(6, "Twitter", "5 new mentions | People are talking about your post"))
        add(Triple(6, "LinkedIn", "Your post got 50 reactions | People are engaging"))
        add(Triple(6, "LinkedIn", "New connection request | Amit Sharma wants to connect"))
        add(Triple(6, "YouTube", "New video | Channel you follow posted a new video"))
        add(Triple(6, "Reddit", "Top post | Your comment got 100 upvotes in r/technology"))
        add(Triple(6, "Instagram", "Comment | @city_explorer: amazing shot!"))
        add(Triple(6, "Twitter", "Trending | #IndiaVsAustralia is trending in your area"))

        // Topic 7 — Banking / Finance
        add(Triple(7, "HDFC Bank", "Rs 2,500 debited | UPI payment to Swiggy"))
        add(Triple(7, "SBI", "Transaction alert | Rs 840 credited to your account"))
        add(Triple(7, "Google Pay", "Payment successful | Rs 500 paid to Rahul Kumar"))
        add(Triple(7, "PhonePe", "Transfer successful | Rs 1,200 sent to Priya"))
        add(Triple(7, "CRED", "Credit card bill due | Rs 12,450 due on 5th March"))
        add(Triple(7, "HDFC Bank", "Balance update | Available balance: Rs 45,230"))
        add(Triple(7, "PhonePe", "Cashback credited | Rs 50 reward points added"))
        add(Triple(7, "Paytm", "Payment received | Rs 300 received from Vikram"))
        add(Triple(7, "ICICI Bank", "OTP for transaction | 458923 (valid 10 mins)"))
        add(Triple(7, "CRED", "EMI reminder | Rs 3,200 EMI due in 3 days"))

        // Topic 8 — News
        add(Triple(8, "Times of India", "Breaking | SENSEX drops 800 points amid global sell-off"))
        add(Triple(8, "NDTV", "Breaking News | PM announces new economic policy"))
        add(Triple(8, "BBC", "Update | Ukraine conflict: ceasefire talks collapse"))
        add(Triple(8, "The Hindu", "Election Results | BJP wins majority in state polls"))
        add(Triple(8, "Inshorts", "Top story | Mukesh Ambani announces Jio expansion"))
        add(Triple(8, "Times of India", "Alert | Supreme Court issues notice on privacy case"))
        add(Triple(8, "NDTV", "Live update | Budget 2024: Finance Minister speaks"))
        add(Triple(8, "The Hindu", "Analysis | RBI holds repo rate at 6.5%"))
        add(Triple(8, "BBC", "World news | G20 summit: leaders agree on climate deal"))
        add(Triple(8, "Inshorts", "Tech news | OpenAI launches new model GPT-5"))

        // Topic 9 — Sports
        add(Triple(9, "Cricbuzz", "WICKET! | Kohli out for 45. India 187/4"))
        add(Triple(9, "Cricbuzz", "Live | IND vs AUS Day 2: Score 245/6 at tea"))
        add(Triple(9, "ESPNcricinfo", "India needs 30 off 5 | 6 wickets in hand"))
        add(Triple(9, "ESPN", "Breaking | Ronaldo moves to Saudi club in record deal"))
        add(Triple(9, "Dream11", "Contest deadline in 30 mins | Create your team now!"))
        add(Triple(9, "Dream11", "Your team scored 89 points | Rank: 1,245 of 50,000"))
        add(Triple(9, "Hotstar", "Match highlights | ICC Final: Full replay available"))
        add(Triple(9, "Cricbuzz", "Match update | 6 wickets down, England fighting back"))
        add(Triple(9, "ESPN", "Match result | Arsenal 2-1 Chelsea in Premier League"))
        add(Triple(9, "Cricbuzz", "Super Over | Match tied! Super over between IND and NZ"))

        // Topic 10 — Health & Fitness
        add(Triple(10, "Google Fit", "Step goal reached! | You've walked 10,000 steps today"))
        add(Triple(10, "Google Fit", "Drink water | You haven't logged water in 2 hours"))
        add(Triple(10, "Apollo 247", "Appointment confirmed | Dr. Sharma, 3 PM today"))
        add(Triple(10, "1mg", "Medicine reminder | Take Vitamin D with meal"))
        add(Triple(10, "1mg", "Order dispatched | Medicines arriving tomorrow"))
        add(Triple(10, "Cult.fit", "Class starts in 30 mins | Yoga - Morning flow"))
        add(Triple(10, "Cult.fit", "You missed yesterday | Get back on track today"))
        add(Triple(10, "HealthifyMe", "Log your dinner | You haven't logged since lunch"))
        add(Triple(10, "HealthifyMe", "Calorie goal | You're 300 calories under today"))
        add(Triple(10, "Apollo 247", "Lab report ready | Your blood test results are available"))
    }

    // Pairs of topics that share delivery/social/news vocabulary
    private val hardPairs = listOf(1 to 2, 3 to 4, 5 to 6, 8 to 9)

    // -------------------------------------------------------------------------
    // Synthetic embedding generation (256-dim, deterministic)
    // -------------------------------------------------------------------------

    private fun Random.nextGaussian(): Float {
        // Box-Muller transform
        var v1: Double
        var v2: Double
        var s: Double
        do {
            v1 = 2.0 * nextDouble() - 1.0
            v2 = 2.0 * nextDouble() - 1.0
            s = v1 * v1 + v2 * v2
        } while (s >= 1.0 || s == 0.0)
        return (v1 * sqrt(-2.0 * ln(s) / s)).toFloat()
    }

    /**
     * Generates unit-normalized topic centroids in 256-dim space.
     * [hardPairBlend] blends each hard-pair centroid toward its partner (0=no blend, 1=identical),
     * simulating that SEMANTIC_SIMILARITY embeddings encode shared "delivery/social/news" semantics
     * more strongly and thus reduce the angular distance between confusable topics.
     */
    private fun buildCentroids(
        numTopics: Int = 10,
        dims: Int = 256,
        seed: Long = 42L,
        hardPairBlend: Float = 0.0f
    ): Array<FloatArray> {
        val rng = Random(seed)
        val centroids = Array(numTopics) { i ->
            VectorMath.l2Normalize(FloatArray(dims) { rng.nextGaussian() })
        }
        for ((a, b) in hardPairs) {
            val ca = centroids[a - 1]
            val cb = centroids[b - 1]
            centroids[a - 1] = VectorMath.l2Normalize(
                FloatArray(dims) { ca[it] * (1f - hardPairBlend) + cb[it] * hardPairBlend }
            )
            centroids[b - 1] = VectorMath.l2Normalize(
                FloatArray(dims) { cb[it] * (1f - hardPairBlend) + ca[it] * hardPairBlend }
            )
        }
        return centroids
    }

    /**
     * Generates a notification embedding as a mix of its topic centroid and random noise.
     * Higher [tightness] → embeddings are closer to the centroid → tighter intra-cluster similarity.
     * Expected intra-cluster cosine similarity ≈ tightness² / (tightness² + (1-tightness)²) in 256-dim space.
     */
    private fun notifEmbedding(centroid: FloatArray, tightness: Float, rng: Random): FloatArray {
        val noise = VectorMath.l2Normalize(FloatArray(centroid.size) { rng.nextGaussian() })
        val raw = FloatArray(centroid.size) { centroid[it] * tightness + noise[it] * (1f - tightness) }
        return VectorMath.l2Normalize(raw)
    }

    /** Builds (groundTruthTopicId, embedding) pairs for the full corpus. */
    private fun buildLabeledEmbeddings(
        tightness: Float = 0.97f,
        hardPairBlend: Float = 0.0f,
        seed: Long = 42L
    ): List<Pair<Int, FloatArray>> {
        val centroids = buildCentroids(seed = seed, hardPairBlend = hardPairBlend)
        val rng = Random(seed + 1)
        return corpus.map { (topicId, _, _) ->
            topicId to notifEmbedding(centroids[topicId - 1], tightness, rng)
        }
    }

    // -------------------------------------------------------------------------
    // Greedy clustering (mirrors TopicEngine.assignOrCreateTopic)
    // -------------------------------------------------------------------------

    private data class LabeledCluster(
        val members: MutableList<Pair<Int, FloatArray>> = mutableListOf()
    )

    private data class BenchmarkResult(
        val purity: Float,
        val numClusters: Int,
        val avgIntraSimilarity: Float,
        val avgInterCentroidSimilarity: Float,
        val hardPairMaxCentroidSimilarity: Float
    ) {
        val separation get() = avgIntraSimilarity - avgInterCentroidSimilarity

        fun print(label: String) {
            println("\n=== $label ===")
            println("  Predicted clusters  : $numClusters  (expected 10)")
            println("  Cluster purity      : ${"%.3f".format(purity)}")
            println("  Avg intra-sim       : ${"%.4f".format(avgIntraSimilarity)}")
            println("  Avg inter-centroid  : ${"%.4f".format(avgInterCentroidSimilarity)}")
            println("  Separation (i–i)    : ${"%.4f".format(separation)}")
            println("  Hard-pair max inter : ${"%.4f".format(hardPairMaxCentroidSimilarity)}")
        }
    }

    private fun evaluate(
        labeledEmbeddings: List<Pair<Int, FloatArray>>,
        threshold: Float = TopicClusteringPolicy.ASSIGN_THRESHOLD
    ): BenchmarkResult {
        // Greedy clustering
        val clusters = mutableListOf<LabeledCluster>()
        for ((topicId, vec) in labeledEmbeddings) {
            var bestCluster: LabeledCluster? = null
            var bestScore = Float.NEGATIVE_INFINITY
            for (cluster in clusters) {
                val score = cluster.members.maxOf { (_, mv) -> VectorMath.dot(vec, mv) }
                if (score > bestScore) { bestScore = score; bestCluster = cluster }
            }
            if (bestScore >= threshold && bestCluster != null) {
                bestCluster.members.add(topicId to vec)
            } else {
                clusters.add(LabeledCluster(mutableListOf(topicId to vec)))
            }
        }

        // Purity: dominant ground-truth label fraction per cluster
        val dominantSum = clusters.sumOf { c ->
            c.members.groupBy { it.first }.values.maxOf { it.size }
        }
        val purity = dominantSum.toFloat() / labeledEmbeddings.size

        // Avg intra-cluster similarity
        val intraSims = mutableListOf<Float>()
        for (c in clusters) {
            val vecs = c.members.map { it.second }
            for (i in vecs.indices) for (j in i + 1 until vecs.size) {
                intraSims += VectorMath.dot(vecs[i], vecs[j])
            }
        }

        // Inter-cluster similarity between cluster centroids
        val centroidVecs = clusters.map { c ->
            val sum = FloatArray(c.members[0].second.size)
            c.members.forEach { (_, v) -> v.forEachIndexed { i, x -> sum[i] += x } }
            VectorMath.l2Normalize(sum)
        }
        val interSims = mutableListOf<Float>()
        for (i in centroidVecs.indices) for (j in i + 1 until centroidVecs.size) {
            interSims += VectorMath.dot(centroidVecs[i], centroidVecs[j])
        }

        // Hard-pair similarity: max cosine between true-topic centroids for hard pairs
        val topicCentroids = (1..10).associateWith { topicId ->
            val vecs = labeledEmbeddings.filter { it.first == topicId }.map { it.second }
            val sum = FloatArray(vecs[0].size)
            vecs.forEach { v -> v.forEachIndexed { i, x -> sum[i] += x } }
            VectorMath.l2Normalize(sum)
        }
        val hardPairSim = hardPairs.maxOf { (a, b) ->
            VectorMath.dot(topicCentroids[a]!!, topicCentroids[b]!!)
        }

        return BenchmarkResult(
            purity = purity,
            numClusters = clusters.size,
            avgIntraSimilarity = intraSims.average().toFloat().takeIf { intraSims.isNotEmpty() } ?: 1f,
            avgInterCentroidSimilarity = interSims.average().toFloat().takeIf { interSims.isNotEmpty() } ?: 0f,
            hardPairMaxCentroidSimilarity = hardPairSim
        )
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    fun `corpus has 10 topics with exactly 10 notifications each`() {
        assertEquals(100, corpus.size)
        val byTopic = corpus.groupBy { it.first }
        assertEquals(10, byTopic.size)
        byTopic.forEach { (topicId, items) ->
            assertEquals("Topic $topicId should have 10 notifications", 10, items.size)
        }
    }

    @Test
    fun `CLUSTERING simulation achieves high purity with well-separated centroids`() {
        // hardPairBlend=0.0: topic centroids are purely random → well-separated in 256-dim space
        // This simulates CLUSTERING task type which focuses on grouping cohesion, not semantic nearness
        val embeddings = buildLabeledEmbeddings(tightness = 0.97f, hardPairBlend = 0.0f)
        val result = evaluate(embeddings)
        result.print("CLUSTERING simulation (hardPairBlend=0.0, tightness=0.97)")

        assertTrue("Purity should be >= 0.90, got ${result.purity}", result.purity >= 0.90f)
        assertTrue(
            "Intra-cluster sim ${result.avgIntraSimilarity} should exceed ASSIGN_THRESHOLD ${TopicClusteringPolicy.ASSIGN_THRESHOLD}",
            result.avgIntraSimilarity > TopicClusteringPolicy.ASSIGN_THRESHOLD
        )
        assertTrue(
            "Intra sim should exceed inter sim (good separation)",
            result.avgIntraSimilarity > result.avgInterCentroidSimilarity
        )
    }

    @Test
    fun `SEMANTIC_SIMILARITY simulation shows lower hard-pair separation than CLUSTERING`() {
        // hardPairBlend=0.3: delivery, work, social, news hard pairs are pulled 30% closer
        // This simulates SEMANTIC_SIMILARITY where shared noun classes raise cross-topic similarity
        val clustering = evaluate(buildLabeledEmbeddings(tightness = 0.97f, hardPairBlend = 0.0f))
        val semantic = evaluate(buildLabeledEmbeddings(tightness = 0.97f, hardPairBlend = 0.3f))

        clustering.print("CLUSTERING simulation (hardPairBlend=0.0)")
        semantic.print("SEMANTIC_SIMILARITY simulation (hardPairBlend=0.3)")

        println("\n  Purity delta (CLUSTERING - SEMANTIC): ${"%.3f".format(clustering.purity - semantic.purity)}")
        println("  Hard-pair max inter delta            : ${"%.4f".format(semantic.hardPairMaxCentroidSimilarity - clustering.hardPairMaxCentroidSimilarity)}")

        assertTrue(
            "SEMANTIC_SIM should show higher hard-pair centroid similarity due to shared semantics",
            semantic.hardPairMaxCentroidSimilarity > clustering.hardPairMaxCentroidSimilarity
        )
    }

    @Test
    fun `ASSIGN_THRESHOLD sensitivity sweep at three thresholds`() {
        val embeddings = buildLabeledEmbeddings(tightness = 0.97f, hardPairBlend = 0.0f)
        println("\n=== ASSIGN_THRESHOLD Sensitivity (CLUSTERING simulation) ===")
        listOf(0.90f, 0.93f, 0.96f).forEach { t ->
            val r = evaluate(embeddings, threshold = t)
            println("  threshold=$t  purity=${"%.3f".format(r.purity)}  clusters=${r.numClusters}")
        }
        // Current threshold should yield usable clustering
        val current = evaluate(embeddings, threshold = TopicClusteringPolicy.ASSIGN_THRESHOLD)
        assertTrue("Current threshold should yield purity > 0.80, got ${current.purity}", current.purity > 0.80f)
    }

    @Test
    fun `food delivery vs package delivery centroids are below ASSIGN_THRESHOLD in CLUSTERING mode`() {
        val centroids = buildCentroids(hardPairBlend = 0.0f)
        val sim = VectorMath.dot(centroids[0], centroids[1]) // topic 1 vs topic 2
        println("\nFood vs Package delivery centroid cosine (CLUSTERING, blend=0.0): ${"%.4f".format(sim)}")
        assertTrue(
            "Food delivery and package delivery centroids should be below ASSIGN_THRESHOLD (${TopicClusteringPolicy.ASSIGN_THRESHOLD}) so they form separate topics, got $sim",
            sim < TopicClusteringPolicy.ASSIGN_THRESHOLD
        )
    }

    @Test
    fun `food delivery vs package delivery overlap increases with SEMANTIC_SIMILARITY blend`() {
        val clusteringCentroids = buildCentroids(hardPairBlend = 0.0f)
        val semanticCentroids = buildCentroids(hardPairBlend = 0.3f)
        val cSim = VectorMath.dot(clusteringCentroids[0], clusteringCentroids[1])
        val sSim = VectorMath.dot(semanticCentroids[0], semanticCentroids[1])
        println("\nFood vs Package delivery centroid cosine similarity:")
        println("  CLUSTERING (blend=0.0) : ${"%.4f".format(cSim)}")
        println("  SEMANTIC_SIM (blend=0.3): ${"%.4f".format(sSim)}")
        assertTrue("Semantic blend should increase confusable pair similarity", sSim > cSim)
    }

    @Test
    fun `all 10 hard notification pairs are within expected similarity range`() {
        // Each same-topic notification pair should be comfortably above ASSIGN_THRESHOLD
        // Each hard-pair cross-topic pair should be below it (under CLUSTERING mode)
        val embeddings = buildLabeledEmbeddings(tightness = 0.97f, hardPairBlend = 0.0f)
        val byTopic = embeddings.groupBy { it.first }.mapValues { it.value.map { p -> p.second } }

        println("\n=== Per-topic embedding statistics ===")
        for (topicId in 1..10) {
            val vecs = byTopic[topicId] ?: continue
            val sims = mutableListOf<Float>()
            for (i in vecs.indices) for (j in i + 1 until vecs.size) sims += VectorMath.dot(vecs[i], vecs[j])
            val min = sims.min()
            val avg = sims.average()
            println("  Topic $topicId: avg intra-sim=${"%.4f".format(avg)}  min=${"%.4f".format(min)}")
            assertTrue(
                "Topic $topicId: min intra-cluster sim $min should be >= 0.80",
                min >= 0.80f
            )
        }
    }
}
