/*
 * Copyright (c) 2010-2010 LinkedIn, Inc
 * Portions Copyright (c) 2011 Yan Pujante
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.linkedin.glu.console.controllers

import org.linkedin.glu.provisioner.plan.api.IStepFilter
import org.linkedin.glu.orchestration.engine.deployment.DeploymentService
import org.linkedin.glu.provisioner.plan.api.IPlanExecutionProgressTracker
import org.linkedin.glu.provisioner.plan.api.IStep
import org.linkedin.glu.provisioner.plan.api.IStepCompletionStatus
import org.linkedin.glu.provisioner.plan.api.Plan
import org.linkedin.glu.provisioner.plan.api.IStepExecution
import org.linkedin.glu.provisioner.plan.api.IPlanExecution
import org.linkedin.glu.console.domain.DbDeployment
import org.linkedin.glu.orchestration.engine.agents.AgentsService
import javax.servlet.http.HttpServletResponse
import org.linkedin.util.clock.Clock
import org.linkedin.util.clock.SystemClock
import org.linkedin.glu.orchestration.engine.deployment.CurrentDeployment
import org.linkedin.glu.orchestration.engine.planner.PlannerService
import org.linkedin.glu.orchestration.engine.action.descriptor.NoOpActionDescriptor
import org.linkedin.glu.provisioner.plan.api.IStep.Type
import org.linkedin.glu.orchestration.engine.action.descriptor.ActionDescriptor
import org.linkedin.groovy.util.json.JsonUtils

/**
 * @author ypujante@linkedin.com */
public class PlanController extends ControllerBase
{
  Clock clock = SystemClock.instance()
  DeploymentService deploymentService
  PlannerService plannerService
  AgentsService agentsService

  def beforeInterceptor = {
    // we make sure that the fabric is always set before executing any action
    return ensureCurrentFabric()
  }

  /**
   * Redirect post into get for plan
   */
  def redirectView = {
    redirect(action: 'view', id: params.planId)
  }

  /**
   * View the plan (expect id)
   */
  def view = {
    def plan = session.plan?.find { it.id == params.id }

    if(plan)
    {
      [plan: plan]
    }
    else
    {
      flash.message = "Plan ${params.id} not found"
    }
  }

  /**
   * Filter the plan (remove the steps that are not selected)
   */
  def filter = {
    def plan = doFilterPlan(params)

    if(plan)
    {
      session.plan = [plan]

      redirect(action: 'view', id: plan.id)
    }
    else
    {
      flash.message = "Plan ${params.id} not found"
      redirect(action: 'view')
    }
  }

  /**
   * Execute the plan
   */
  def execute = {
    Plan plan = doFilterPlan(params)

    if(plan)
    {
      session.plan = null

      CurrentDeployment currentDeployment =
        deploymentService.executeDeploymentPlan(request.system,
                                                plan,
                                                plan.name,
                                                new ProgressTracker())

      redirect(action: 'deployments', id: currentDeployment.id)
    }
    else
    {
      flash.message = "Plan ${params.id} not found"
      redirect(action: 'view')
    }
  }

  /**
   * Create a plan
   */
  def create = {

    def args = params
    if(params.json)
    {
      args = JsonUtils.fromJSON(params.json)
    }

    if(args.systemFilter)
      args.system = request.system.filterBy(args.systemFilter)
    else
      args.system = request.system
    args.type = args.stepType ?: Type.SEQUENTIAL

    if(args.planType)
    {
      Plan<ActionDescriptor> plan =
        plannerService."compute${args.planType.capitalize()}Plan"(args, null)
      if(plan?.hasLeafSteps())
      {
        session.plan = plan
        render(template: 'plan', model: [plan: plan])
      }
      else
        render "no plan"
    }
    else
      render "choose a plan"
  }

  /**
   * View deployments
   */
  def deployments = {
    if(params.id)
    {
      render(view: 'deploymentDetails', model: [deployment: deploymentService.getDeployment(params.id)])
    }
    else
    {
      def deployments = sortAndGroupDeployments(deploymentService.getDeployments(request.fabric.name))

      [groupBy: deployments]
    }
  }

  private def sortAndGroupDeployments(deployments)
  {
    deployments?.sort() { d1, d2 ->
        d2.planExecution.startTime.compareTo(d1.planExecution.startTime)
    }?.groupBy { it.planExecution.isCompleted() ? 'Completed' : 'Active'}
  }

  /**
   * Renders the deployment details (just the inner part)
   */
  def renderDeploymentDetails = {
    if(params.id)
    {
      render(template: 'deploymentDetails', model: [deployment: deploymentService.getDeployment(params.id)])
    }
  }

  /**
   * Renders only the completed deployments */
  def renderDeployments = {
    def deployments = sortAndGroupDeployments(deploymentService.getDeployments(request.fabric.name))
    render(template: 'deployments', model: [groupBy: deployments])
  }

  /**
   * View archived deployments
   */
  def archived = {
    if(params.id)
    {
      [deployment: DbDeployment.findByIdAndFabric(params.id, request.fabric.name)]
    }
    else
    {
      params.max = Math.min(params.max ? params.max.toInteger() : 50, 50)
      params.sort = 'startDate'
      params.order = 'desc'
      [
          deployments: DbDeployment.findAllByFabric(request.fabric.name, params),
          count: DbDeployment.count(),
      ]
    }
  }

  /**
   * Remove the deployement
   */
  def archiveDeployment = {
    deploymentService.archiveDeployment(params.id)
    redirect(action: 'deployments')
  }

  /**
   * Archives all deployments */
  def archiveAllDeployments = {
    def count = deploymentService.archiveAllDeployments(request.fabric.name)
    flash.message = "Successfully archived ${count} deployment(s)."
    redirect(action: 'deployments')
  }

  /**
   * Resume the deployment
   */
  def resumeDeployment = {
    audit('plan.resume', params.id)
    deploymentService.getDeployment(params.id)?.planExecution?.resume()
    redirect(action: 'deployments', id: params.id)
  }

  /**
   * Pause the deployment
   */
  def pauseDeployment = {
    audit('plan.pause', params.id)
    deploymentService.getDeployment(params.id)?.planExecution?.pause()
    redirect(action: 'deployments', id: params.id)
  }

  /**
   * Aborts the deployment
   */
  def abortDeployment = {
    audit('plan.abort', params.id)
    deploymentService.getDeployment(params.id)?.planExecution?.cancel(true)
    redirect(action: 'deployments', id: params.id)
  }


  /**
   * Cancels a single step
   */
  def cancelStep = {
    def stepExecutor = deploymentService.getDeployment(params.id)?.progressTracker?.steps?.getAt(params.stepId)
    if(stepExecutor)
    {
      audit('plan.cancelStep', "plan: ${params.id}, step: ${stepExecutor.step.name}")
      stepExecutor.cancel(true)
    }
    redirect(action: 'deployments', id: params.id)
  }

  /**
   * Filter the plans by the steps selected.
   */
  private Plan doFilterPlan(params)
  {
    Plan plan = session.plan.find { it.id == params.id }

    if(plan)
    {
      def stepIds = new HashSet()
      params.stepId?.each {
        stepIds << it
      }

      def filter = { step ->
        stepIds.contains(step.id)
      }

      def planBuilder = plan.toPlanBuilder(filter as IStepFilter)

      plan = planBuilder.toPlan()

      return plan
    }
    else
    {
      return null
    }
  }

  /**
   * Returns the list of all plans
   */
  def rest_list_plans = {
    render "ok"
  }

  /**
   * Create a plan
   */
  def rest_create_plan = {

    def args = [:]
    args.system = request.system
    args.fabric = request.fabric

    try
    {
      args.type = IStep.Type.valueOf((params.order ?: 'sequential').toUpperCase())
    }
    catch (IllegalArgumentException e)
    {
      render e.message
      response.sendError(HttpServletResponse.SC_BAD_REQUEST)
      return
    }

    Plan plan

    def metadata = [origin: 'rest', action: params.planAction, filter: params.systemFilter ?: 'all']

    switch(params.planAction)
    {
      case 'start':
      case 'deploy':
        plan = plannerService.computeDeployPlan(args, metadata)
        break;

      case 'stop':
        args.state = ['stopped']
        plan = plannerService.computeTransitionPlan(args, metadata)
        break;

      case 'undeploy':
        plan = plannerService.computeUndeployPlan(args, metadata)
        break;

      case 'bounce':
        plan = plannerService.computeBouncePlan(args, metadata)
        break;

      case 'redeploy':
        plan = plannerService.computeRedeployPlan(args, metadata)
        break;

      default:
        render "invalid action: ${params.planAction}"
        response.sendError(HttpServletResponse.SC_BAD_REQUEST)
        return
    }

    if(plan?.hasLeafSteps())
    {
      if(plan.leafSteps.findAll { it.action instanceof NoOpActionDescriptor }.size() == plan.leafStepsCount)
      {
        response.setStatus(HttpServletResponse.SC_NO_CONTENT,
                           'no plan created (only pending transitions)')
        render ''
      }
      else
      {
        deploymentService.savePlan(plan)
        response.addHeader('Location', g.createLink(absolute: true,
                                                    mapping: 'restPlan',
                                                    id: plan.id, params: [fabric: request.fabric]).toString())
        response.setStatus(HttpServletResponse.SC_CREATED)
        render plan.id
      }
    }
    else
    {
      response.setStatus(HttpServletResponse.SC_NO_CONTENT, 'no plan created (no delta)')
      render ''
    }
  }

  /**
   * View a plan
   */
  def rest_view_plan = {
    def plan = deploymentService.getPlan(params.id)
    if(plan)
    {
      response.setContentType('text/xml')
      render plan.toXml()
    }
    else
    {
      response.sendError HttpServletResponse.SC_NOT_FOUND
    }
  }

  /**
   *  Execute a plan
   */
  def rest_execute_plan = {
    def plan = deploymentService.getPlan(params.id)
    if(plan)
    {
      CurrentDeployment currentDeployment =
        deploymentService.executeDeploymentPlan(request.system,
                                                plan,
                                                plan.name,
                                                new ProgressTracker())

      response.addHeader('Location', g.createLink(absolute: true,
                                                  mapping: 'restExecution',
                                                  id: currentDeployment.id, params: [
                                                  planId: plan.id,
                                                  fabric: request.fabric.name]).toString())
      response.setStatus(HttpServletResponse.SC_CREATED)
      render currentDeployment.id
    }
    else
    {
      response.sendError HttpServletResponse.SC_NOT_FOUND
    }
  }

  /**
   * Get plan execution progression status (percentage)
   */
  def rest_execution_status = {
    def deployment = deploymentService.getDeployment(params.id)

    if(deployment && deployment.planExecution.plan.id == params.planId)
    {
      def completionStatus

      def completion = deployment.progressTracker.completionPercentage
      if(deployment.planExecution.isCompleted())
      {
        completion = "100:${deployment.planExecution.completionStatus.status}"
      }
      else
        completion = "${completion}"
      response.setHeader("X-LinkedIn-GLU-Completion", completion)
      render ''
    }
    else
    {
      response.sendError HttpServletResponse.SC_NOT_FOUND
    }
  }

  /**
   * Get a full plan execution (xml format)
   */
  def rest_view_execution = {
    def deployment = deploymentService.getDeployment(params.id)

    if(deployment && deployment.planExecution.plan.id == plans[params.planId])
    {
      response.setContentType('text/xml')
      render deployment.planExecution.toXml()
    }
    else
    {
      response.sendError HttpServletResponse.SC_NOT_FOUND
    }
  }
}

/**
 * Keep track of the progress to be able to display it in the UI.
 */
class ProgressTracker implements IPlanExecutionProgressTracker
{
  Clock clock = SystemClock.instance()

  Plan plan
  long planStartTime
  long planEndTime

  def _steps = [:]

  int _leafStepsCompletedCount = 0

  public void onPlanStart(IPlanExecution planExecution)
  {
    this.plan = planExecution.plan
    planStartTime = clock.currentTimeMillis()
  }

  public void onPlanEnd(IStepCompletionStatus stepExecutionStatus)
  {
    planEndTime = clock.currentTimeMillis()
  }

  public void onPause(IStep step)
  {

  }

  public void onResume(IStep step)
  {

  }

  public void onCancelled(IStep step)
  {

  }

  synchronized int getLeafStepsCompletedCount()
  {
    return _leafStepsCompletedCount
  }

  synchronized int getCompletionPercentage()
  {
    if(plan.leafStepsCount == 0)
      return 100;

    return (((double) _leafStepsCompletedCount / (double) plan.leafStepsCount) * 100.0) as int
  }

  def synchronized getSteps()
  {
    def res = [:]
    _steps.values().each { res[it.step.id] = it }
    return res
  }

  public synchronized void onStepStart(IStepExecution stepExecution)
  {
    _steps[stepExecution.step.id] = stepExecution
  }

  public synchronized void onStepEnd(IStepCompletionStatus stepExecutionStatus)
  {
    if(stepExecutionStatus.step.type == IStep.Type.LEAF)
      _leafStepsCompletedCount++
  }
}