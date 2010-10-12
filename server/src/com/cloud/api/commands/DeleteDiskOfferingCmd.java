/**
 *  Copyright (C) 2010 Cloud.com, Inc.  All rights reserved.
 * 
 * This software is licensed under the GNU General Public License v3 or later.
 * 
 * It is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * 
 */
package com.cloud.api.commands;

import org.apache.log4j.Logger;

import com.cloud.api.BaseCmd;
import com.cloud.api.BaseCmd.Manager;
import com.cloud.api.Implementation;
import com.cloud.api.Parameter;
import com.cloud.api.ServerApiException;
import com.cloud.api.response.SuccessResponse;

@Implementation(method="deleteDiskOffering", manager=Manager.ConfigManager, description="Updates a disk offering.")
public class DeleteDiskOfferingCmd extends BaseCmd {
    public static final Logger s_logger = Logger.getLogger(DeleteDiskOfferingCmd.class.getName());
    private static final String s_name = "deletediskofferingresponse";

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////

    @Parameter(name="id", type=CommandType.LONG, required=true, description="ID of the disk offering")
    private Long id;


    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////

    public Long getId() {
        return id;
    }


    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////


    public String getName() {
        return s_name;
    }
  
    @Override @SuppressWarnings("unchecked")
    public SuccessResponse getResponse() {
    	 SuccessResponse response = new SuccessResponse();
         Boolean responseObject = (Boolean)getResponseObject();
       
         if (responseObject != null) {
         	response.setSuccess(responseObject);
         } else {
             throw new ServerApiException(BaseCmd.INTERNAL_ERROR, "Failed to delete disk offering");
         }

         response.setResponseName(getName());
         return response;
    }
}
