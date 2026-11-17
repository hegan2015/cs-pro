package com.qoder.cs.core.ticket;

import com.qoder.cs.facade.dto.TicketNoteDTO;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface TicketNoteMapper {

    @Select("SELECT NEXT VALUE FOR seq_note")
    long nextId();

    @Insert("INSERT INTO cs_ticket_note(id, ticket_id, author, content, created_at) " +
            "VALUES(#{id}, #{ticketId}, #{author}, #{content}, #{createdAt})")
    int insert(TicketNoteDTO note);

    @Select("SELECT * FROM cs_ticket_note WHERE ticket_id = #{ticketId} ORDER BY created_at, id")
    List<TicketNoteDTO> selectByTicket(@Param("ticketId") String ticketId);
}