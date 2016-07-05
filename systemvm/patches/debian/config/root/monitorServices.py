#!/usr/bin/python
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.





import ConfigParser
import logging
import os
import subprocess
import time

SUCCESS = 0
FAILED = 1
INVALID_INP = 2
RUNNING = 3
STOPPED = 4
STARTING = 5

LOG_INFO = 'INFO'
LOG_ALERT = 'ALERT'
LOG_CRIT = 'CRIT'
LOG_NOTIF = 'NOTIF'

MONIT_AFTER_MINS = 30
SLEEP_SEC = 1
RETRY_ITERATIONS = 10
RETRY_FOR_RESTART = 5
MONITOR_LOG = '/var/log/monitor.log'
UNMONIT_PS_FILE = '/etc/unmonit_psList.txt'


def get_config(config_file_path="/etc/monitor.conf"):
    """
    Reads the process configuration from the config file.
    Config file contains the processes to be monitored.

    """
    process_dict = {}
    parser = ConfigParser.SafeConfigParser()
    parser.read(config_file_path)


    for section in parser.sections():
        process_dict[section] = {}

        for name, value in parser.items(section):
            process_dict[section][name] = value
#           printd (" %s = %r" % (name, value))

    return  process_dict

def printd(msg):
    """
    prints the debug messages

    .. Note:: Actually does nothing (return 0).
    """

    #for debug
    #print msg
    # XXX: replace printd as the logging module does everything?
    # ie: logging.setLevel()
    return 0

    # XXX: Unreachable code
    fileobj = open(MONITOR_LOG, 'r+')
    fileobj.seek(0, 2)
    fileobj.write(str(msg)+"\n")
    fileobj.close()

def raisealert(severity, msg, process_name=None):
    """ Writes the alert message"""

    #timeStr=str(time.ctime())
    if process_name is not None:
        log = '['+severity +']'+" " + '['+process_name+']' + " " + msg +"\n"
    else:
        log = '['+severity+']' + " " + msg +"\n"

    # XXX: basicConfig should be called once
    logging.basicConfig(
        level=logging.INFO,
        filename='/var/log/routerServiceMonitor.log',
        format='%(asctime)s %(message)s'
    )
    logging.info(log)
    # XXX: why not use an additionnal handler
    # ie: https://docs.python.org/2/library/logging.handlers.html#sysloghandler
    subprocess.Popen(
        ('logger', '-t', 'monit', log),
        shell=True,
        stdout=subprocess.PIPE,
    )


def pid_match_pidfile(pidfile, pids):
    """ Compares the running process pid with the pid in pid file.
        If a process with multiple pids then it matches with pid file
    """

    if not pids or not isinstance(pids, list) or not len(pids):
        printd("Invalid Arguments")
        return FAILED
    if not os.path.isfile(pidfile):
        #It seems there is no pid file for this service
        printd("The pid file %s is not there for this process" % pidfile)
        return FAILED

    try:
        inp = open(pidfile, 'r').read()
    except IOError:
        printd("pid file: %s open failed" % pidfile)
        return FAILED

    if not inp:
        return FAILED

    printd("file content %s" % inp)
    printd(pids)
    tocheck_pid = inp.strip()
    for item in pids:
        if tocheck_pid == item.strip():
            printd("pid file matched")
            return SUCCESS

    return FAILED

def process_running_status(process_name, pid_file):
    '''
    :param str process_name: name of the process to be checked
    :param str pid_file: name of the file to check
    :return: True if PIDs found match pid_file and the PIDs in a list
    :rtype: tuple
    '''
    printd("checking the process %s" % process_name)
    pids = []
    cmd = ('pidof', process_name)
    printd(' '.join(cmd))

    pout = subprocess.Popen(cmd, shell=True, stdout=subprocess.PIPE)
    temp_out = pout.communicate()[0]

    #check there is only one pid or not
    if pout.returncode == 0:
        pids = temp_out.split(' ')
        printd("pid(s) of process %s are %s " %(process_name, pids))

        #there is more than one process so match the pid file
        #if not matched set pidFileMatched=False
        printd("Checking pid file")
        if pid_match_pidfile(pid_file, pids) == SUCCESS:
            return True, pids

    printd("pid of exit status %s" % pout.returncode)

    return False, pids

def restart_service(service_name):
    '''basically a ``service X restart``

    :param str service_name: name of the service to be restarted
    :return: potential success
    :rtype: bool
    '''
    proc = subprocess.Popen(
        ('service', service_name, 'restart'),
        shell=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )

    if proc.wait() == 0:
        printd("The service %s recovered successfully" % service_name)
        msg = "The process %s is recovered successfully" % service_name
        raisealert(LOG_INFO, msg, service_name)
        return True
    else:
        printd("process restart failed ....")

    return False



def process_status(process):
    """
    Check the process running status, if not running tries to restart
    """
    process_name = process.get('processname')
    service_name = process.get('servicename')
    pidfile = process.get('pidfile')
    #temp_out = None
    restart_failed = False
    pids = ''
    cmd = ''
    if process_name is None:
        printd("\n Invalid Process Name")
        return INVALID_INP

    status, pids = process_running_status(process_name, pidfile)

    if status:
        printd("The process is running ....")
        return RUNNING
    else:
        printd("Process %s is not running trying to recover" %process_name)
        #Retry the process state for few seconds

        for i in range(1, RETRY_ITERATIONS):
            time.sleep(SLEEP_SEC)

            if i < RETRY_FOR_RESTART: # this is just for trying few more times

                status, pids = process_running_status(process_name, pidfile)
                if status:
                    raisealert(LOG_ALERT, "The process detected as running", process_name)
                    break
                else:
                    printd("Process %s is not running checking the status again..." %process_name)
                    continue
            else:
                msg = "The process " +process_name+" is not running trying recover "
                raisealert(LOG_INFO, process_name, msg)

                if service_name == 'apache2':
                    # Killing apache2 process with this the main service will not start
                    for pid in pids:
                        cmd = 'kill -9 '+pid
                        printd(cmd)
                        subprocess.Popen(
                            cmd,
                            shell=True,
                            stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT,
                        )

                if restart_service(service_name):
                    break
                else:
                    restart_failed = True
                    continue
        #for end here

        if restart_failed:
            msg = "The process %s recover failed "%process_name
            raisealert(LOG_ALERT, process_name, msg)

            printd("Restart failed after number of retries")
            return STOPPED

    return RUNNING


def monitor_process(processes_info):
    """
    Monitors the processes which got from the config file
    """
    if not len(processes_info):
        printd("Invalid Input")
        return INVALID_INP

    dict_unmonit = {}
    umonit_update = {}
    unmonit_ps = False

    if not os.path.isfile(UNMONIT_PS_FILE):
        printd('Unmonit File not exist')
    else:
        #load the dictionary with unmonit process list
        dict_unmonit = load_ps_from_unmonit_file()

    #time for noting process down time
    csec = time.time()

    for process, properties in processes_info.items():
        #skip the process it its time stamp less than MONIT_AFTER_MINS
        printd("checking the service %s \n" %process)

        if dict_unmonit:
            if dict_unmonit.has_key(process):
                ts = dict_unmonit[process]

                if not check_ps_timestamp_for_monitor(csec, ts, properties):
                    unmonit_ps = True
                    continue

        if process_status(properties) != RUNNING:
            printd("\n Service %s is not Running"%process)
            #add this process into unmonit list
            printd("updating the service for unmonit %s\n" %process)
            umonit_update[process] = csec

    #if dict is not empty write to file else delete it
    if umonit_update:
        write_pslist_to_unmonit_file(umonit_update)
    elif not umonit_update and not unmonit_ps:
        #delete file it is there
        unlink(UNMONIT_PS_FILE)


def check_ps_timestamp_for_monitor(csec, ts, process):
    '''
    :param int csec: actual time in seconds
    :param int ts: previous time in seconds
    :param str process: process name
    :return: True if need to be monitored
    :rtype: int
    '''
    printd("Time difference=%d" % (csec - ts))
    tmin = (csec - ts)/60

    if tmin < MONIT_AFTER_MINS:
        raisealert(
            LOG_ALERT,
            "The %s get monitor after %s minutes " % (
                process,
                MONIT_AFTER_MINS,
            )
        )
        printd(
            'process will be monitored after %s min' % (
                MONIT_AFTER_MINS - tmin
            )
        )
        return False

    return True

def unlink(filename):
    '''a verbose :func:`os.unlink`
    '''
    if os.path.isfile(filename):
        printd("Removing the file %s" %filename)
        os.remove(filename)

def load_ps_from_unmonit_file():
    '''load unmonit list from UNMONIT_PS_FILE
    '''
    dict_unmonit = {}

    try:
        data = open(UNMONIT_PS_FILE).read()
    except IOError:
        printd("Failed to open file %s " %(UNMONIT_PS_FILE))
        return FAILED

    if not data:
        printd("File %s content is empty " %UNMONIT_PS_FILE)
        return FAILED

    printd(data)
    for item in data.split(','):
        key, val = item.split(':')
        dict_unmonit[key] = int(val)

    return dict_unmonit


# XXX: replace *unmonit_file with json.dump/load?
def write_pslist_to_unmonit_file(umonit_update):
    '''write unmonit list to UNMONIT_PS_FILE
    '''
    printd("Write updated unmonit list to file")
    data = [
        '%s:%d' % (process_name, seconds)
        for process_name, seconds in umonit_update.items()
    ]
    line = ','.join(data)
    printd(line)
    try:
        open(UNMONIT_PS_FILE, 'w').write(line)
    except IOError:
        printd("Failed to open file %s " %UNMONIT_PS_FILE)
        return FAILED


def main():
    # Step1 : Get Config
    printd("monitoring started")
    temp_dict = get_config()

    # Step2: Monitor and Raise Alert
    monitor_process(temp_dict)

if __name__ == "__main__":
    main()
