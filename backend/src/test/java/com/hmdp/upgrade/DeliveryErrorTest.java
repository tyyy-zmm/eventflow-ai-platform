package com.hmdp.upgrade;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.dao.DataAccessResourceFailureException;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class DeliveryErrorTest {
    Trading trading=mock(Trading.class);
    Transactions tx=mock(Transactions.class);
    JdbcTemplate db=mock(JdbcTemplate.class);
    @SuppressWarnings("unchecked") KafkaTemplate<String,String> kafka=mock(KafkaTemplate.class);
    Delivery delivery=new Delivery(db,tx,trading,kafka,new ObjectMapper(),mock(Admission.class),mock(Reservations.class),"test",false);
    ConsumerRecord<String,String> valid() {
        return new ConsumerRecord<>("test",0,1,"1","{\"schemaVersion\":1,\"eventId\":\"e\",\"requestId\":\"r\",\"activityId\":1}");
    }
    @Test void committedOrderAcknowledgesWithoutCallingRedisRepair() throws Exception {
        var reservations=mock(Reservations.class);var admission=mock(Admission.class);
        var consumer=new Delivery(db,tx,trading,kafka,new ObjectMapper(),admission,reservations,"test",false);
        when(trading.process(any())).thenReturn(new Trading.Request("r",1,1,"hash","REJECTED","SOLD_OUT",java.time.Instant.now()));
        doThrow(new DataAccessResourceFailureException("redis offline")).when(admission).soldOut(1);
        var ack=mock(Acknowledgment.class);consumer.consume(valid(),ack);
        verify(ack).acknowledge();verifyNoInteractions(reservations);verifyNoInteractions(db);
    }
    @Test void databaseFailureNeverAcknowledges() {
        var ack=mock(Acknowledgment.class);
        doThrow(new DataAccessResourceFailureException("offline")).when(trading).process(any());
        assertThrows(DataAccessResourceFailureException.class,()->delivery.consume(valid(),ack));verifyNoInteractions(ack);
    }
    @Test void poisonPersistenceFailureNeverAcknowledges() {
        var ack=mock(Acknowledgment.class);
        when(tx.run(any())).thenThrow(new DataAccessResourceFailureException("offline"));
        assertThrows(DataAccessResourceFailureException.class,()->delivery.consume(new ConsumerRecord<>("test",0,1,"1","bad"),ack));
        verifyNoInteractions(ack);verifyNoInteractions(trading);
    }
    @Test void unknownSchemaDoesNotReachTrading() throws Exception {
        var ack=mock(Acknowledgment.class);
        delivery.consume(new ConsumerRecord<>("test",0,1,"1","{\"schemaVersion\":99,\"eventId\":\"e\",\"requestId\":\"r\",\"activityId\":1}"),ack);
        verifyNoInteractions(trading);verify(tx).run(any());verify(ack).acknowledge();
    }
    @Test void kafkaKeyMismatchDoesNotReachTrading() throws Exception {
        var ack=mock(Acknowledgment.class);var message=valid();
        delivery.consume(new ConsumerRecord<>("test",0,1,"2",message.value()),ack);
        verifyNoInteractions(trading);verify(tx).run(any());verify(ack).acknowledge();
    }
}
