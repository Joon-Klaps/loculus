package org.loculus.backend.controller

import io.minio.MakeBucketArgs
import io.minio.MinioClient
import io.minio.SetBucketPolicyArgs
import mu.KotlinLogging
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.platform.engine.support.descriptor.ClassSource
import org.junit.platform.engine.support.descriptor.MethodSource
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestPlan
import org.loculus.backend.api.Address
import org.loculus.backend.api.NewGroup
import org.loculus.backend.config.BackendSpringProperty
import org.loculus.backend.controller.datauseterms.DataUseTermsControllerClient
import org.loculus.backend.controller.files.FilesClient
import org.loculus.backend.controller.groupmanagement.GroupManagementControllerClient
import org.loculus.backend.controller.seqsetcitations.SeqSetCitationsControllerClient
import org.loculus.backend.controller.submission.SubmissionControllerClient
import org.loculus.backend.controller.submission.SubmissionConvenienceClient
import org.loculus.backend.service.datauseterms.DATA_USE_TERMS_TABLE_NAME
import org.loculus.backend.service.files.FILES_TABLE_NAME
import org.loculus.backend.service.groupmanagement.GROUPS_TABLE_NAME
import org.loculus.backend.service.groupmanagement.USER_GROUPS_TABLE_NAME
import org.loculus.backend.service.submission.CURRENT_PROCESSING_PIPELINE_TABLE_NAME
import org.loculus.backend.service.submission.METADATA_UPLOAD_AUX_TABLE_NAME
import org.loculus.backend.service.submission.SEQUENCE_ENTRIES_PREPROCESSED_DATA_TABLE_NAME
import org.loculus.backend.service.submission.SEQUENCE_ENTRIES_TABLE_NAME
import org.loculus.backend.service.submission.SEQUENCE_UPLOAD_AUX_TABLE_NAME
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.annotation.AliasFor
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.containers.MinIOContainer
import org.testcontainers.containers.PostgreSQLContainer

/**
 * The main annotation for tests. It also loads the [EndpointTestExtension], which initializes
 * a PostgreSQL test container.
 * You can set additional properties to - for example - override the backend config file, like in
 * [org.loculus.backend.controller.submission.GetReleasedDataDataUseTermsDisabledEndpointTest].
 */
@Target(AnnotationTarget.TYPE, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@AutoConfigureMockMvc
@SpringBootTest
@ActiveProfiles("with-database")
@ExtendWith(EndpointTestExtension::class)
@DirtiesContext
@Import(
    SubmissionControllerClient::class,
    SubmissionConvenienceClient::class,
    GroupManagementControllerClient::class,
    DataUseTermsControllerClient::class,
    SeqSetCitationsControllerClient::class,
    PublicJwtKeyConfig::class,
    FilesClient::class,
)
annotation class EndpointTest(@get:AliasFor(annotation = SpringBootTest::class) val properties: Array<String> = [])

const val SINGLE_SEGMENTED_REFERENCE_GENOME = "src/test/resources/backend_config_single_segment.json"

const val DATA_USE_TERMS_DISABLED_CONFIG = "src/test/resources/backend_config_data_use_terms_disabled.json"

const val S3_CONFIG = "src/test/resources/backend_config_s3.json"

private const val SPRING_DATASOURCE_URL = "spring.datasource.url"
private const val SPRING_DATASOURCE_USERNAME = "spring.datasource.username"
private const val SPRING_DATASOURCE_PASSWORD = "spring.datasource.password"

const val ACCESSION_SEQUENCE_NAME = "accession_sequence"
const val DEFAULT_GROUP_NAME = "testGroup"
const val DEFAULT_GROUP_NAME_CHANGED = "testGroup name updated"
val DEFAULT_GROUP = NewGroup(
    groupName = DEFAULT_GROUP_NAME,
    institution = "testInstitution",
    address = Address(
        line1 = "testAddressLine1",
        line2 = "testAddressLine2",
        postalCode = "testPostalCode",
        city = "testCity",
        state = "testState",
        country = "testCountry",
    ),
    contactEmail = "testEmail",
)
val DEFAULT_GROUP_CHANGED = NewGroup(
    groupName = DEFAULT_GROUP_NAME_CHANGED,
    institution = "Updated institution",
    address = Address(
        line1 = "Updated address line 1",
        line2 = "Updated address line 2",
        postalCode = "Updated post code",
        city = "Updated city",
        state = "Updated state",
        country = "Updated country",
    ),
    contactEmail = "Updated email",
)

const val DEFAULT_USER_NAME = "testuser"
const val SUPER_USER_NAME = "test_superuser"
const val ALTERNATIVE_DEFAULT_GROUP_NAME = "testGroup2"
const val ALTERNATIVE_DEFAULT_USER_NAME = "testUser2"

val ALTERNATIVE_DEFAULT_GROUP = NewGroup(
    groupName = ALTERNATIVE_DEFAULT_GROUP_NAME,
    institution = "alternativeTestInstitution",
    address = Address(
        line1 = "alternativeTestAddressLine1",
        line2 = "alternativeTestAddressLine2",
        postalCode = "alternativeTestPostalCode",
        city = "alternativeTestCity",
        state = "alternativeTestState",
        country = "alternativeTestCountry",
    ),
    contactEmail = "alternativeTestEmail",
)

val MINIO_TEST_REGION = "testregion"
val MINIO_TEST_BUCKET = "testbucket"

private val log = KotlinLogging.logger { }

class EndpointTestExtension :
    BeforeEachCallback,
    TestExecutionListener {
    companion object {
        private val postgres: PostgreSQLContainer<*> = PostgreSQLContainer<Nothing>("postgres:latest")
        private val minio: MinIOContainer = MinIOContainer("minio/minio:latest").withReuse(true)
        private var isStarted = false
        private var isBucketCreated = false
    }

    override fun testPlanExecutionStarted(testPlan: TestPlan) {
        if (!isStarted) {
            isAnnotatedWithEndpointTest(testPlan) {
                postgres.start()
                minio.start()
                isStarted = true
                if (!isBucketCreated) {
                    createBucket(minio, MINIO_TEST_REGION, MINIO_TEST_BUCKET)
                    isBucketCreated = true
                }
            }
        }

        log.info {
            "Started Postgres container: ${postgres.jdbcUrl}, user ${postgres.username}, pw ${postgres.password}"
            "Started MinIO container: ${minio.s3URL}, user ${minio.userName}, pw ${minio.password}"
        }

        System.setProperty(SPRING_DATASOURCE_URL, postgres.jdbcUrl)
        System.setProperty(SPRING_DATASOURCE_USERNAME, postgres.username)
        System.setProperty(SPRING_DATASOURCE_PASSWORD, postgres.password)

        System.setProperty(BackendSpringProperty.S3_ENABLED, "true")
        System.setProperty(BackendSpringProperty.S3_BUCKET_ENDPOINT, minio.s3URL)
        System.setProperty(BackendSpringProperty.S3_BUCKET_REGION, MINIO_TEST_REGION)
        System.setProperty(BackendSpringProperty.S3_BUCKET_BUCKET, MINIO_TEST_BUCKET)
        System.setProperty(BackendSpringProperty.S3_BUCKET_ACCESS_KEY, minio.userName)
        System.setProperty(BackendSpringProperty.S3_BUCKET_SECRET_KEY, minio.password)
    }

    private fun isAnnotatedWithEndpointTest(testPlan: TestPlan, callback: () -> Unit) {
        for (root in testPlan.roots) {
            testPlan.getChildren(root).forEach { testIdentifier ->
                testIdentifier.source.ifPresent { testSource ->
                    when (testSource) {
                        is MethodSource -> {
                            val testClass = Class.forName(testSource.className)
                            val method = testClass.getMethod(testSource.methodName)
                            if (method.isAnnotationPresent(EndpointTest::class.java)) {
                                callback()
                            }
                        }

                        is ClassSource -> {
                            val testClass = Class.forName(testSource.className)
                            if (testClass.isAnnotationPresent(EndpointTest::class.java)) {
                                callback()
                            }
                        }
                    }
                }
            }
        }
    }

    override fun beforeEach(context: ExtensionContext) {
        log.debug("Clearing database")
        val result = postgres.execInContainer(
            "psql",
            "-U",
            postgres.username,
            "-d",
            postgres.databaseName,
            "-c",
            clearDatabaseStatement(),
        )
        if (result.exitCode != 0) {
            throw RuntimeException(
                "Database clearing failed with exit code ${result.exitCode}. Stderr: ${result.stderr}",
            )
        }
    }

    override fun testPlanExecutionFinished(testPlan: TestPlan) {
        postgres.stop()
        minio.stop()

        System.clearProperty(SPRING_DATASOURCE_URL)
        System.clearProperty(SPRING_DATASOURCE_USERNAME)
        System.clearProperty(SPRING_DATASOURCE_PASSWORD)

        System.clearProperty(BackendSpringProperty.S3_ENABLED)
        System.clearProperty(BackendSpringProperty.S3_BUCKET_ENDPOINT)
        System.clearProperty(BackendSpringProperty.S3_BUCKET_REGION)
        System.clearProperty(BackendSpringProperty.S3_BUCKET_BUCKET)
        System.clearProperty(BackendSpringProperty.S3_BUCKET_ACCESS_KEY)
        System.clearProperty(BackendSpringProperty.S3_BUCKET_SECRET_KEY)
    }
}

private fun clearDatabaseStatement(): String = """
        truncate table 
            $GROUPS_TABLE_NAME,
            $SEQUENCE_ENTRIES_TABLE_NAME,
            $SEQUENCE_ENTRIES_PREPROCESSED_DATA_TABLE_NAME,
            $USER_GROUPS_TABLE_NAME,
            $METADATA_UPLOAD_AUX_TABLE_NAME,
            $SEQUENCE_UPLOAD_AUX_TABLE_NAME,
            $DATA_USE_TERMS_TABLE_NAME,
            $CURRENT_PROCESSING_PIPELINE_TABLE_NAME,
            $FILES_TABLE_NAME
            cascade;
        alter sequence $ACCESSION_SEQUENCE_NAME restart with 1;
        insert into $CURRENT_PROCESSING_PIPELINE_TABLE_NAME values
            (1, now(), '$DEFAULT_ORGANISM'),
            (1, now(), '$OTHER_ORGANISM'),
            (1, now(), '$ORGANISM_WITHOUT_CONSENSUS_SEQUENCES');
    """

private fun createBucket(container: MinIOContainer, region: String, bucket: String) {
    val minioClient = MinioClient
        .builder()
        .endpoint(container.s3URL)
        .credentials(container.userName, container.password)
        .build()
    minioClient.makeBucket(
        MakeBucketArgs.builder()
            .region(region)
            .bucket(bucket)
            .build(),
    )
    val policy = """
    {
      "Version":"2012-10-17",
      "Statement":[
        {
          "Effect":"Allow",
          "Principal":"*",
          "Action":"s3:GetObject",
          "Resource":["arn:aws:s3:::$bucket/*"],
          "Condition":{
            "StringEquals":{
              "s3:ExistingObjectTag/public":"true"
            }
          }
        }
      ]
    }
    """.trimIndent()

    minioClient.setBucketPolicy(SetBucketPolicyArgs.builder().bucket(bucket).region(region).config(policy).build())
}
