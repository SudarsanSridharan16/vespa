// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include <vespa/document/base/testdocman.h>
#include <vespa/document/bucket/fixed_bucket_spaces.h>
#include <vespa/document/repo/documenttyperepo.h>
#include <vespa/document/update/documentupdate.h>
#include <vespa/storage/distributor/operations/external/read_for_write_visitor_operation.h>
#include <vespa/storage/distributor/operations/external/visitoroperation.h>
#include <vespa/storage/distributor/distributor.h>
#include <vespa/storage/distributor/distributormetricsset.h>
#include <vespa/storageapi/message/persistence.h>
#include <vespa/storageapi/message/visitor.h>
#include <tests/distributor/distributortestutil.h>
#include <vespa/vespalib/gtest/gtest.h>

using namespace ::testing;
using document::Bucket;
using document::BucketId;

namespace storage::distributor {

struct ReadForWriteVisitorOperationStarterTest : Test, DistributorTestUtil {
    document::TestDocMan            _test_doc_man;
    VisitorOperation::Config        _default_config;
    std::unique_ptr<OperationOwner> _op_owner;
    BucketId                        _superbucket;
    BucketId                        _sub_bucket;

    ReadForWriteVisitorOperationStarterTest()
        : _test_doc_man(),
          _default_config(100, 100),
          _op_owner(),
          _superbucket(16, 4),
          _sub_bucket(17, 4)
    {}

    void SetUp() override {
        createLinks();
        setupDistributor(1, 1, "version:1 distributor:1 storage:1");
        _op_owner = std::make_unique<OperationOwner>(_sender, getClock());

        addNodesToBucketDB(_sub_bucket, "0=1/2/3/t");
    }

    void TearDown() override {
        close();
    }

    static Bucket default_bucket(BucketId id) {
        return Bucket(document::FixedBucketSpaces::default_space(), id);
    }

    std::shared_ptr<VisitorOperation> create_nested_visitor_op(bool valid_command = true) {
        auto cmd = std::make_shared<api::CreateVisitorCommand>(
                document::FixedBucketSpaces::default_space(), "reindexingvisitor", "foo", "");
        if (valid_command) {
            cmd->addBucketToBeVisited(_superbucket);
            cmd->addBucketToBeVisited(BucketId()); // Will be inferred to first sub-bucket in DB
        }
        return std::make_shared<VisitorOperation>(
                getExternalOperationHandler(), getExternalOperationHandler(),
                getDistributorBucketSpace(), cmd, _default_config,
                getDistributor().getMetrics().visits);
    }

    OperationSequencer& operation_sequencer() {
        return getExternalOperationHandler().operation_sequencer();
    }

    std::shared_ptr<ReadForWriteVisitorOperationStarter> create_rfw_op(std::shared_ptr<VisitorOperation> visitor_op) {
        return std::make_shared<ReadForWriteVisitorOperationStarter>(
                std::move(visitor_op), operation_sequencer(),
                *_op_owner, getDistributor().getPendingMessageTracker());
    }
};

TEST_F(ReadForWriteVisitorOperationStarterTest, visitor_that_fails_precondition_checks_is_immediately_failed) {
    auto op = create_rfw_op(create_nested_visitor_op(false));
    _op_owner->start(op, OperationStarter::Priority(120));
    EXPECT_EQ("CreateVisitorReply(last=BucketId(0x0000000000000000)) "
              "ReturnCode(ILLEGAL_PARAMETERS, No buckets in CreateVisitorCommand for visitor 'foo')",
              _sender.getLastReply());
}

TEST_F(ReadForWriteVisitorOperationStarterTest, visitor_immediately_started_if_no_pending_ops_to_bucket) {
    auto op = create_rfw_op(create_nested_visitor_op(true));
    _op_owner->start(op, OperationStarter::Priority(120));
    ASSERT_EQ("Visitor Create => 0", _sender.getCommands(true));
}

TEST_F(ReadForWriteVisitorOperationStarterTest, visitor_start_deferred_if_pending_ops_to_bucket) {
    auto op = create_rfw_op(create_nested_visitor_op(true));
    // Pending mutating op to same bucket, prevents visitor from starting
    auto update = std::make_shared<document::DocumentUpdate>(
            _test_doc_man.getTypeRepo(),
            *_test_doc_man.getTypeRepo().getDocumentType("testdoctype1"),
            document::DocumentId("id::testdoctype1:n=4:foo"));
    auto update_cmd = std::make_shared<api::UpdateCommand>(
            default_bucket(document::BucketId(0)), std::move(update), api::Timestamp(0));

    Operation::SP mutating_op;
    getExternalOperationHandler().handleMessage(update_cmd, mutating_op);
    ASSERT_TRUE(mutating_op);
    _op_owner->start(mutating_op, OperationStarter::Priority(120));
    ASSERT_EQ("Update(BucketId(0x4400000000000004), id::testdoctype1:n=4:foo, timestamp 1) => 0",
              _sender.getCommands(true, true));
    // Since pending message tracking normally happens in the distributor itself during sendUp,
    // we have to emulate this and explicitly insert the sent message into the pending mapping.
    getDistributor().getPendingMessageTracker().insert(_sender.command(0));

    _op_owner->start(op, OperationStarter::Priority(120));
    // Nothing started yet
    ASSERT_EQ("", _sender.getCommands(true, false, 1));

    // Pretend update operation completed
    auto update_reply = std::shared_ptr<api::StorageReply>(_sender.command(0)->makeReply());
    getDistributor().getPendingMessageTracker().reply(*update_reply);
    _op_owner->handleReply(update_reply);

    // Visitor should now be started!
    ASSERT_EQ("Visitor Create => 0", _sender.getCommands(true, false, 1));
}

}
